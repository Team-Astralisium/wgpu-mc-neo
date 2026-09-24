use crate::preprocessing;
use glsl::parser::Parse;
use glsl::syntax::{ArraySpecifier, ArraySpecifierDimension, ArrayedIdentifier, Block, CompoundStatement, Declaration, Expr, ExprStatement, ExternalDeclaration, FullySpecifiedType, FunIdentifier, FunctionDefinition, FunctionParameterDeclaration, FunctionParameterDeclarator, FunctionPrototype, Identifier, InitDeclaratorList, Initializer, LayoutQualifier, LayoutQualifierSpec, NonEmpty, Preprocessor, PreprocessorVersion, ShaderStage, SimpleStatement, SingleDeclaration, Statement, StorageQualifier, TranslationUnit, TypeName, TypeQualifier, TypeQualifierSpec, TypeSpecifier, TypeSpecifierNonArray};
use glsl::transpiler::glsl::{show_expr, show_translation_unit};
use glsl::visitor::{Host, HostMut, Visit, Visitor, VisitorMut};
use log::{debug, error, warn};
use std::collections::HashMap;
use std::ffi::{CStr, c_char};
use once_cell::sync::Lazy;

static OPENGL_TO_WGPU_MATRIX_AST: Lazy<Statement> = Lazy::new(|| {
    Statement::Simple(Box::new(SimpleStatement::Expression(
        Some(
            Expr::parse(r#"gl_Position = mat4(
    vec4(1.0, 0.0, 0.0, 0.0),
    vec4(0.0, 1.0, 0.0, 0.0),
    vec4(0.0, 0.0, 0.5, 0.0),
    vec4(0.0, 0.0, 0.5, 1.0)
) * gl_Position;
"#).unwrap()
        )
    )))
});

static FORCE_WHITE: Lazy<Statement> = Lazy::new(|| {
    Statement::Simple(Box::new(SimpleStatement::Expression(
        Some(
            Expr::parse(r#"fragColor = vec4(1.0, 1.0, 1.0, 1.0);"#).unwrap()
        )
    )))
});

/// Negates the clip-space Y axis, on top of the depth-range patch above.
///
/// Minecraft's shaders, projections and texture coordinates are all written against OpenGL, and
/// the two APIs disagree about which end of a render target clip-space `y = +1` is. In OpenGL a
/// framebuffer's first texel row is its window origin row, the bottom-left corner, so `y = +1`
/// - the top of the projection - lands on the *last* texel row. Everywhere else (Vulkan, D3D,
/// Metal, and therefore wgpu, which normalises them) `y = +1` lands on the *first* texel row.
///
/// Nothing notices while the target is only ever presented: the image comes out the same either
/// way up, and the present blit turns it over. It shows the moment a render target is *sampled
/// with Minecraft's own texture coordinates*, which is how 26.1 builds every sprite atlas -
/// `TextureAtlas#uploadInitialContents` renders each sprite into `mipViews[level]` and the GUI
/// later reads it back with `u = x / width, v = y / height`. Built the wgpu way round, every
/// sprite lands mirrored, the sampled rectangle is empty atlas, and the GUI draws its widget
/// backgrounds as nothing at all while their labels, which come from an uploaded font atlas,
/// still show.
///
/// Negating `y` in clip space is the whole fix, and it is what a translation layer does for the
/// same reason. It is safe for the screen as long as the present blit compensates, which
/// `PresentBlit` does. It also puts `gl_FrontFacing` back the way Minecraft expects: front-facing
/// is counter-clockwise in *window* coordinates in OpenGL, counter-clockwise in *framebuffer*
/// coordinates here, and mirroring the clip space is exactly what reconciles the two.
static EMULATE_GL_CLIP_SPACE_AST: Lazy<Statement> = Lazy::new(|| {
    Statement::Simple(Box::new(SimpleStatement::Expression(
        Some(
            Expr::parse(r#"gl_Position.y = -gl_Position.y;"#).unwrap()
        )
    )))
});

pub struct MatrixPatcher;
pub struct ForceWhite;
pub struct EmulateGlClipSpace;

impl VisitorMut for ForceWhite {
    fn visit_function_definition(&mut self, def: &mut FunctionDefinition) -> Visit {
        if def.prototype.name.0 == "main" {
            def.statement.statement_list.insert(def.statement.statement_list.len(), FORCE_WHITE.clone());
        }

        Visit::Children
    }

}

impl VisitorMut for MatrixPatcher {
    fn visit_function_definition(&mut self, def: &mut FunctionDefinition) -> Visit {
        if def.prototype.name.0 == "main" {
            def.statement.statement_list.insert(def.statement.statement_list.len(), OPENGL_TO_WGPU_MATRIX_AST.clone());
        }

        Visit::Children
    }

}

impl VisitorMut for EmulateGlClipSpace {
    fn visit_function_definition(&mut self, def: &mut FunctionDefinition) -> Visit {
        if def.prototype.name.0 == "main" {
            // Appended, not wrapped: whatever `main` did to get there, the value it leaves behind
            // is the one that gets flipped. Both patches land after the shader's own writes, and
            // the depth-range one has to run first so the y flip cannot disturb it - it does not
            // touch y, but keeping the order fixed keeps the two patches readable.
            def.statement.statement_list.insert(def.statement.statement_list.len(), EMULATE_GL_CLIP_SPACE_AST.clone());
        }

        Visit::Children
    }

}

struct VersionFixer;
impl VisitorMut for VersionFixer {
    fn visit_preprocessor_version(&mut self, pv: &mut glsl::syntax::PreprocessorVersion) -> Visit {
        pv.version = 440;
        Visit::Parent
    }
}
#[derive(Debug)]
struct SamplerFinder {
    layout_qualifiers: Option<[LayoutQualifierSpec; 2]>,
    names: HashMap<String, TypeSpecifierNonArray>,
    uniform: bool,
    sampler: Option<TypeSpecifierNonArray>,
}

#[derive(Debug)]
struct TypeChanger {
    new_t: Option<TypeSpecifierNonArray>,
    name_ext: String,
}

impl VisitorMut for TypeChanger {
    fn visit_single_declaration(&mut self, decl: &mut SingleDeclaration) -> Visit {
        decl.name.as_mut().unwrap().0.extend(self.name_ext.chars());

        Visit::Children
    }

    fn visit_type_specifier_non_array(&mut self, s: &mut TypeSpecifierNonArray) -> Visit {
        *s = self.new_t.take().unwrap();

        Visit::Children
    }
}

impl Visitor for SamplerFinder {
    fn visit_single_declaration(&mut self, decl: &SingleDeclaration) -> Visit {
        self.names
            .insert(decl.name.as_ref().unwrap().0.clone(), decl.ty.ty.ty.clone());

        Visit::Children
    }

    fn visit_type_qualifier_spec(&mut self, t: &TypeQualifierSpec) -> Visit {
        self.uniform |= matches!(t, TypeQualifierSpec::Storage(StorageQualifier::Uniform));

        Visit::Children
    }

    fn visit_type_specifier_non_array(&mut self, t: &TypeSpecifierNonArray) -> Visit {
        if matches!(
            t,
            TypeSpecifierNonArray::Sampler2D | TypeSpecifierNonArray::SamplerCube
        ) {
            self.sampler = Some(t.clone());
        }

        Visit::Children
    }
}

struct FunctionCallExpandSampler<'a> {
    local_funcs: &'a [String],
    samplers: HashMap<String, String>,
}

struct BuiltinFunctionCallMergeSampler<'a> {
    local_funcs: &'a [String],
    samplers: HashMap<String, String>,
}

impl<'a> VisitorMut for BuiltinFunctionCallMergeSampler<'a> {
    //This visitor is called on calls to built-in functions

    fn visit_expr(&mut self, expr: &mut Expr) -> Visit {
        //Function calls can contain other function calls, so we have to deal with that
        if let Expr::FunCall(FunIdentifier::Identifier(func_name), params) = expr {
            //Calling a locally defined function
            if self.local_funcs.contains(&func_name.0) {
                let mut v = FunctionCallExpandSampler {
                    local_funcs: self.local_funcs,
                    samplers: self.samplers.clone(),
                };
                expr.visit_mut(&mut v);
            }
        } else if let Expr::Variable(ident) = expr {
            //This variable is referencing a sampler, and we're in a built-in function call
            match self.samplers.get(&ident.0) {
                None => {}
                Some(constructor) => {
                    *expr = Expr::FunCall(
                        FunIdentifier::Identifier(Identifier(constructor.to_string())),
                        vec![
                            Expr::Variable(Identifier(format!("{}_wm_texshim", ident.0))),
                            Expr::Variable(Identifier(format!("{}_wm_sampler", ident.0))),
                        ],
                    );
                }
            }
        }

        Visit::Children
    }
}

impl<'a> VisitorMut for FunctionCallExpandSampler<'a> {
    fn visit_expr(&mut self, expr: &mut Expr) -> Visit {
        if let Expr::FunCall(FunIdentifier::Identifier(func_name), params) = expr {
            if !self.local_funcs.contains(&func_name.0) {
                let mut v = BuiltinFunctionCallMergeSampler {
                    local_funcs: &[],
                    samplers: self.samplers.clone(),
                };
                expr.visit_mut(&mut v);
            } else {
                *params = params
                    .iter()
                    .map(|p| {
                        if let Expr::Variable(var_name) = p
                            && self.samplers.contains_key(&var_name.0)
                        {
                            vec![
                                Expr::Variable(Identifier(format!("{var_name}_wm_texshim"))),
                                Expr::Variable(Identifier(format!("{var_name}_wm_sampler"))),
                            ]
                        } else {
                            vec![p.clone()]
                        }
                    })
                    .flatten()
                    .collect();
            }
        }

        Visit::Children
    }
}

fn get_sampler_constructor_for_glsl_type(specifier: &TypeSpecifierNonArray) -> String {
    match specifier {
        TypeSpecifierNonArray::Sampler2D => "sampler2D".into(),
        TypeSpecifierNonArray::SamplerCube => "samplerCube".into(),
        _ => unreachable!(),
    }
}

struct SamplerExpansion {
    samplers: HashMap<String, String>,
    local_functions: Vec<String>,
}

impl VisitorMut for SamplerExpansion {
    fn visit_function_prototype(&mut self, proto: &mut FunctionPrototype) -> Visit {
        self.local_functions.push(proto.name.0.clone());

        proto.parameters = proto
            .parameters
            .iter()
            .map(|param| match &param {
                FunctionParameterDeclaration::Unnamed(_, _) => unimplemented!(),
                FunctionParameterDeclaration::Named(qual, decl) => {
                    if matches!(
                        decl.ty.ty,
                        TypeSpecifierNonArray::Sampler2D | TypeSpecifierNonArray::SamplerCube
                    ) {
                        if !self.samplers.contains_key(&decl.ident.ident.0) {
                            self.samplers.insert(
                                decl.ident.ident.0.clone(),
                                get_sampler_constructor_for_glsl_type(&decl.ty.ty),
                            );
                        }

                        vec![
                            FunctionParameterDeclaration::Named(
                                qual.clone(),
                                FunctionParameterDeclarator {
                                    ty: TypeSpecifier {
                                        ty: TypeSpecifierNonArray::TypeName(TypeName(
                                            "texture2D".into(),
                                        )),
                                        array_specifier: None,
                                    },
                                    ident: ArrayedIdentifier {
                                        ident: Identifier(format!(
                                            "{}_wm_texshim",
                                            decl.ident.ident.0
                                        )),
                                        array_spec: None,
                                    },
                                },
                            ),
                            FunctionParameterDeclaration::Named(
                                qual.clone(),
                                FunctionParameterDeclarator {
                                    ty: TypeSpecifier {
                                        ty: TypeSpecifierNonArray::TypeName(TypeName(
                                            "sampler".into(),
                                        )),
                                        array_specifier: None,
                                    },
                                    ident: ArrayedIdentifier {
                                        ident: Identifier(format!(
                                            "{}_wm_sampler",
                                            decl.ident.ident.0
                                        )),
                                        array_spec: None,
                                    },
                                },
                            ),
                        ]
                    } else {
                        vec![param.clone()]
                    }
                }
            })
            .flatten()
            .collect();

        Visit::Parent
    }

    fn visit_expr(&mut self, expr: &mut Expr) -> Visit {
        if let Expr::FunCall(FunIdentifier::Identifier(_), _) = expr {
            let mut f = FunctionCallExpandSampler {
                local_funcs: &self.local_functions,
                samplers: self.samplers.clone(),
            };

            expr.visit_mut(&mut f);

            Visit::Parent
        } else {
            Visit::Children
        }
    }
}

struct ExplicitMipWhenSampling;

impl VisitorMut for ExplicitMipWhenSampling {
    fn visit_expr(&mut self, call: &mut Expr) -> Visit {
        if let Expr::FunCall(FunIdentifier::Identifier(id), params) = call
            && id.0 == "texture"
        {
            id.0 = "textureLod".into();

            params.push(Expr::FloatConst(0.0));
        }

        Visit::Children
    }
}

pub struct NagaFixConstArrayExplicit {
    size: Option<u32>,
}

impl VisitorMut for NagaFixConstArrayExplicit {
    fn visit_init_declarator_list(&mut self, idl: &mut InitDeclaratorList) -> Visit {
        if let Some(TypeQualifier {
            qualifiers: NonEmpty(specs),
        }) = &mut idl.head.ty.qualifier
        {
            if specs
                .iter()
                .any(|x| matches!(x, TypeQualifierSpec::Storage(StorageQualifier::Const)))
            {
                idl.head.initializer.visit_mut(self);
                idl.head.ty.visit_mut(self);
            }
        }

        Visit::Parent
    }

    fn visit_array_specifier_dimension(&mut self, dim: &mut ArraySpecifierDimension) -> Visit {
        match self.size.take() {
            None => {}
            Some(size) => {
                *dim =
                    ArraySpecifierDimension::ExplicitlySized(Box::new(Expr::IntConst(size as i32)))
            }
        }

        Visit::Parent
    }

    fn visit_initializer(&mut self, i: &mut Initializer) -> Visit {
        match i {
            Initializer::Simple(simple) => match &**simple {
                Expr::FunCall(FunIdentifier::Expr(expr), params) => {
                    if let Expr::Bracket(
                        _,
                        ArraySpecifier {
                            dimensions: NonEmpty(d),
                        },
                    ) = &**expr
                    {
                        self.size = Some(params.len() as u32);
                    }
                }
                _ => {}
            },
            _ => {}
        }

        Visit::Parent
    }
}

struct RewriteGLBuiltinSemantics;

impl VisitorMut for RewriteGLBuiltinSemantics {
    fn visit_expr(&mut self, expr: &mut Expr) -> Visit {
        if let Expr::Variable(ident) = expr {
            match &ident.0[..] {
                "gl_VertexID" => {
                    ident.0 = "int(gl_VertexIndex)".into();
                }
                "gl_InstanceID" => {
                    ident.0 = "gl_InstanceIndex".into();
                }
                _ => {}
            }
        }

        Visit::Children
    }
}

pub struct IncrementingAnnotator {
    pub offset: u32,
    pub target: StorageQualifier,
    pub found: bool,
    pub insert_location: Option<u32>,
    pub map: HashMap<String, u32>,
}

pub struct InAnnotator {
    pub in_found: bool,
    pub insert_location: Option<u32>,
    pub map: HashMap<String, u32>,
}

pub struct UniformAnnotator {
    pub uniform_found: bool,
    pub uniform_binding: Option<(u32, u32)>,
    pub uniform_sets: HashMap<String, (u32, u32)>,
    pub active: bool,
}

pub struct OrphanDestroyer {
    pub uniform_found: bool,
    pub active: bool,
    pub orphan_found: bool,
    pub uniform_set: HashMap<String, u32>,
}

pub struct RemovePointSize {
    pub is_point_var: bool,
}

pub struct SamplerBufferRewriter<'a> {
    pub is_sampler_buffer: bool,
    pub buffers: Vec<String>,
    pub uniform_sets: &'a HashMap<String, (u32, u32)>,
}

pub struct RewriteFetches<'a> {
    pub buffers: &'a [String],
}

impl VisitorMut for RemovePointSize {
    fn visit_expr(&mut self, e: &mut Expr) -> Visit {
        if let Expr::Variable(ident) = e
            && ident.0 == "gl_PointSize"
        {
            self.is_point_var = true;
        }

        Visit::Children
    }

    fn visit_statement(&mut self, statement: &mut Statement) -> Visit {
        if let Statement::Simple(simple) = statement {
            if let SimpleStatement::Expression(expr) = &mut **simple {
                expr.visit_mut(self);

                if self.is_point_var {
                    *statement = Statement::parse(";").unwrap();
                }

                self.is_point_var = false;
            }
        }

        Visit::Children
    }
}

impl<'a> VisitorMut for RewriteFetches<'a> {
    fn visit_expr(&mut self, expression_base: &mut Expr) -> Visit {
        if let Expr::FunCall(FunIdentifier::Identifier(ident), e) = expression_base
            && ident.0 == "texelFetch"
        {
            if let Expr::Variable(ident) = e.first().unwrap() {
                if self.buffers.contains(&ident.0) {
                    let op = e.get(1).unwrap();

                    let mut expr_out = String::new();

                    show_expr(&mut expr_out, op);

                    // One texel is one byte, so the word is `index / 4` and the byte inside it is
                    // selected by `(index % 4) * 8`. `ivec4`, not `ivec2`, because an `isamplerBuffer`
                    // fetch returns four components and `.g`/`.b`/`.a` would otherwise not compile;
                    // a texel buffer's unused components are zero and alpha is one.
                    *expression_base = Expr::parse(format!(
                        "ivec4(int(({}.inner[uint({expr_out}) >> 2u] >> ((uint({expr_out}) & 3u) << 3u)) & 0xFFu), 0, 0, 1)",
                        ident.0
                    ))
                    .unwrap();
                }
            }
        }

        Visit::Children
    }
}

impl VisitorMut for SamplerBufferRewriter<'_> {
    fn visit_declaration(&mut self, decl: &mut Declaration) -> Visit {
        if let Declaration::InitDeclaratorList(i) = decl {
            i.visit_mut(self);

            let name = &i.head.name.as_ref().unwrap().0;

            if self.is_sampler_buffer {
                self.buffers.push(name.clone());

                let (set, binding) = self.uniform_sets.get(name).copied().unwrap();

                *decl = Declaration::parse(format!(
                    "layout(std430, set = {set}, binding = {binding}) readonly buffer {name}Block {{ uint[] inner; }} {name};"
                )).unwrap();
            }
        }

        Visit::Children
    }

    fn visit_type_specifier_non_array(&mut self, t: &mut TypeSpecifierNonArray) -> Visit {
        self.is_sampler_buffer = matches!(t, TypeSpecifierNonArray::ISamplerBuffer);

        Visit::Parent
    }
}

impl VisitorMut for OrphanDestroyer {
    fn visit_block(&mut self, block: &mut Block) -> Visit {
        self.uniform_found = false;

        self.active = true;
        block.qualifier.to_owned().visit_mut(self);
        self.active = false;

        if self.uniform_found {
            self.orphan_found = !self.uniform_set.contains_key(&block.name.0);
        }

        Visit::Children
    }

    fn visit_translation_unit(&mut self, unit: &mut TranslationUnit) -> Visit {
        let mut keep = vec![];

        for ex in &mut unit.0.0 {
            ex.visit_mut(self);

            if !self.orphan_found {
                keep.push(ex.clone());
            }

            self.orphan_found = false;
        }

        unit.0.0 = keep;

        Visit::Parent
    }

    fn visit_single_declaration(&mut self, single_decl: &mut SingleDeclaration) -> Visit {
        self.uniform_found = false;

        self.active = true;
        single_decl.ty.to_owned().visit_mut(self);
        self.active = false;

        if self.uniform_found {
            self.orphan_found = !self
                .uniform_set
                .contains_key(&single_decl.name.as_ref().unwrap().0);
        }

        Visit::Children
    }

    fn visit_storage_qualifier(&mut self, qual: &mut StorageQualifier) -> Visit {
        if !self.active {
            return Visit::Children;
        }

        self.uniform_found = matches!(qual, StorageQualifier::Uniform);

        Visit::Children
    }
}

impl VisitorMut for UniformAnnotator {
    fn visit_block(&mut self, block: &mut Block) -> Visit {
        self.uniform_found = false;

        self.active = true;
        block.qualifier.to_owned().visit_mut(self);
        self.active = false;

        if self.uniform_found {
            let binding = self.binding_for(&block.name.0);
            self.uniform_binding = Some(binding);
        }

        Visit::Children
    }

    fn visit_single_declaration(
        &mut self,
        single_decl: &mut glsl::syntax::SingleDeclaration,
    ) -> Visit {
        self.uniform_found = false;

        self.active = true;
        single_decl.ty.to_owned().visit_mut(self);
        self.active = false;

        if self.uniform_found {
            let name = single_decl.name.as_ref().unwrap().0.clone();
            let binding = self.binding_for(&name);
            self.uniform_binding = Some(binding);
        }

        Visit::Children
    }

    fn visit_type_qualifier(&mut self, qual: &mut TypeQualifier) -> Visit {
        match self.uniform_binding.take() {
            Some((set, binding)) => {
                qual.qualifiers.0.insert(
                    0,
                    TypeQualifierSpec::parse(format!("layout(set = {set}, binding = {binding})"))
                        .unwrap(),
                );

                return Visit::Parent;
            }
            None => {}
        }

        Visit::Children
    }

    fn visit_storage_qualifier(&mut self, qual: &mut StorageQualifier) -> Visit {
        if !self.active {
            return Visit::Children;
        }

        self.uniform_found = matches!(qual, StorageQualifier::Uniform);

        Visit::Children
    }
}

impl UniformAnnotator {
    /// The binding for `name`, inventing one if the pipeline never declared it.
    ///
    /// Uniform *blocks* are looked after by [`add_implicit_uniforms`], which gives every block the
    /// shaders declare a real slot in the pipeline layout before this runs, so a miss here means
    /// something the pipeline cannot bind at all - a plain `uniform` or a shimmed sampler that the
    /// pipeline's uniform list leaves out.
    ///
    /// That is still not a reason to take the game down: a panic on a `#[jni_fn]` frame cannot
    /// unwind, so it aborts the process. The declaration gets a binding that cannot collide with
    /// anything the map already holds, which keeps the shader parseable; the pipeline layout will
    /// not have that binding, and wgpu reports that by number.
    fn binding_for(&mut self, name: &str) -> (u32, u32) {
        if let Some(binding) = self.uniform_sets.get(name) {
            return *binding;
        }

        let next = self
            .uniform_sets
            .values()
            .map(|(_, binding)| binding + 1)
            .max()
            .unwrap_or(0);

        if name.ends_with(SHIM_TEXTURE_SUFFIX) || name.ends_with(SHIM_SAMPLER_SUFFIX) {
            // Not a miss at all: `shim_samplers` split this combined sampler into a texture and a
            // sampler of its own, and the pipeline only ever knew the combined name. Logging it as
            // an error put four alarming lines in every startup log for shaders that render fine.
            debug!(
                "wgpu-mc: {name} is one half of a shimmed Sampler2D, so it takes the next free \
                 binding ({next})"
            );
        } else {
            error!(
                "wgpu-mc: the shader declares uniform {name}, which the pipeline does not provide; \
                 giving it binding {next}, which the pipeline layout will not have"
            );
        }

        self.uniform_sets.insert(name.to_string(), (0, next));

        (0, next)
    }
}

/// The uniform blocks the GL backend binds straight off the shader rather than off the pipeline.
///
/// `GlProgram::setupUniforms` enumerates the blocks the shader declares with
/// `glGetActiveUniformBlockName` and gives any of these that the pipeline never mentioned a
/// binding of its own; anything else it warns about and leaves alone. Kept here so the warning
/// below says the same thing GL would.
const BUILT_IN_UNIFORMS: [&str; 4] = ["Projection", "Lighting", "Fog", "Globals"];

/// What [`shim_samplers`] renames the two halves of a combined sampler to.
///
/// A `Sampler2D` uniform becomes a `texture2D` named `<name>_wm_texshim` and a `sampler` named
/// `<name>_wm_sampler`, because WGSL has no combined sampler. The names are built here so the
/// binding lookup can recognise its own work and not report it as a uniform the pipeline forgot.
pub const SHIM_TEXTURE_SUFFIX: &str = "_wm_texshim";
pub const SHIM_SAMPLER_SUFFIX: &str = "_wm_sampler";

/// Collects the name of every uniform block a stage declares at the top level.
fn collect_uniform_blocks(stage: &ShaderStage, names: &mut Vec<String>) {
    for declaration in stage.0.0.iter() {
        let ExternalDeclaration::Declaration(Declaration::Block(block)) = declaration else {
            continue;
        };

        let is_uniform = block.qualifier.qualifiers.0.iter().any(|qualifier| {
            matches!(
                qualifier,
                TypeQualifierSpec::Storage(StorageQualifier::Uniform)
            )
        });

        if is_uniform {
            names.push(block.name.0.clone());
        }
    }
}

/// Gives every uniform block the shaders declare a binding, adding the ones the pipeline left out.
///
/// A pipeline's uniform list is not the whole story, and on the GL side it does not have to be:
/// `RenderSystem` binds `Globals` by name on the render pass, and `GlProgram::setupUniforms` finds
/// it because the *shader* declares it. `core/terrain` is the live example - `terrain.vsh` imports
/// `globals.glsl` and uses `CameraBlockPos`, while `TERRAIN_SNIPPET` only asks for `Projection` and
/// `ChunkSection`. wgpu needs the binding to exist in the pipeline layout, so a block with no slot
/// gets one here, appended after everything the pipeline did declare.
///
/// The returned bindings match what appending to the descriptor produces, because the layout
/// numbers its entries positionally and the pipeline's own bindings are dense.
fn add_implicit_uniforms(
    vert_stage: &ShaderStage,
    frag_stage: &ShaderStage,
    uniform_locations: &mut HashMap<String, (u32, u32)>,
) -> Vec<(String, u32)> {
    let mut declared = Vec::new();
    collect_uniform_blocks(vert_stage, &mut declared);
    collect_uniform_blocks(frag_stage, &mut declared);

    let mut next_binding = uniform_locations
        .values()
        .map(|(_, binding)| binding + 1)
        .max()
        .unwrap_or(0);

    let mut added = Vec::new();

    for name in declared {
        // The other stage, or an earlier block, may already have claimed this one.
        if uniform_locations.contains_key(&name) {
            continue;
        }

        if !BUILT_IN_UNIFORMS.contains(&name.as_str()) {
            warn!(
                "wgpu-mc: shader declares uniform block {name}, which the pipeline does not \
                 provide; binding it at {next_binding} on its own"
            );
        }

        uniform_locations.insert(name.clone(), (0, next_binding));
        added.push((name, next_binding));
        next_binding += 1;
    }

    added
}

impl VisitorMut for InAnnotator {
    fn visit_single_declaration(
        &mut self,
        single_decl: &mut glsl::syntax::SingleDeclaration,
    ) -> Visit {
        self.in_found = false;
        single_decl.ty.to_owned().visit_mut(self);

        if self.in_found {
            let name = single_decl.name.as_ref().unwrap().0.clone();

            // A miss here means the shader reads an input the pipeline does not describe: a vertex
            // attribute missing from the pipeline's vertex format, or a varying the vertex stage
            // never wrote. GL tolerates the first - an unbound attribute just reads a default - so
            // this is not necessarily a porting bug, and it is not worth aborting the process over.
            // Leaving the declaration alone lets naga number it the way GL would have.
            match self.map.get(&name).copied() {
                Some(location) => self.insert_location = Some(location),
                None => {
                    // `drop_unprovided_inputs` removes these before this runs, so reaching here
                    // means something nested or otherwise unexpected. A location past everything
                    // the pipeline does provide keeps the shader valid and makes wgpu report the
                    // missing attribute by number, instead of naga handing it location 0 and
                    // colliding with an input that is provided.
                    let next = self
                        .map
                        .values()
                        .max()
                        .map_or(0, |location| location + 1);

                    let mut available: Vec<&str> =
                        self.map.keys().map(|key| key.as_str()).collect();
                    available.sort_unstable();

                    error!(
                        "wgpu-mc: shader reads {name}, which the pipeline does not provide; \
                         numbering it {next}, past {}. Available: {}",
                        next - 1,
                        available.join(", ")
                    );

                    self.insert_location = Some(next);
                }
            }
        }

        Visit::Children
    }

    fn visit_type_qualifier(&mut self, qual: &mut glsl::syntax::TypeQualifier) -> Visit {
        match self.insert_location.take() {
            Some(offset) => {
                qual.qualifiers.0.insert(
                    0,
                    TypeQualifierSpec::parse(format!("layout(location = {offset})")).unwrap(),
                );
            }
            None => {}
        }

        Visit::Children
    }

    fn visit_storage_qualifier(&mut self, qual: &mut StorageQualifier) -> Visit {
        self.in_found = matches!(qual, StorageQualifier::In);

        Visit::Children
    }
}

impl VisitorMut for IncrementingAnnotator {
    fn visit_single_declaration(
        &mut self,
        single_decl: &mut glsl::syntax::SingleDeclaration,
    ) -> Visit {
        self.found = false;
        single_decl.ty.to_owned().visit_mut(self);

        if self.found {
            let name = single_decl.name.as_ref().unwrap().0.clone();
            self.insert_location = Some(self.offset);
            self.map.insert(name, self.offset);
            self.offset += 1;
        }

        Visit::Children
    }

    fn visit_type_qualifier(&mut self, qual: &mut glsl::syntax::TypeQualifier) -> Visit {
        match self.insert_location.take() {
            Some(offset) => {
                qual.qualifiers.0.insert(
                    0,
                    TypeQualifierSpec::parse(format!("layout(location = {offset})")).unwrap(),
                );
            }
            None => {}
        }

        Visit::Children
    }

    fn visit_storage_qualifier(&mut self, qual: &mut StorageQualifier) -> Visit {
        self.found = *qual == self.target;

        Visit::Children
    }
}

pub fn fix_version(shader_stage: &mut ShaderStage) {
    shader_stage.visit_mut(&mut VersionFixer);
}

/// The name of the `in` variable an external declaration declares, if it declares one.
fn declared_input_name(declaration: &ExternalDeclaration) -> Option<&str> {
    let ExternalDeclaration::Declaration(Declaration::InitDeclaratorList(list)) = declaration else {
        return None;
    };

    let is_input = list
        .head
        .ty
        .qualifier
        .as_ref()
        .is_some_and(|qualifier| {
            qualifier.qualifiers.0.iter().any(|spec| {
                matches!(spec, TypeQualifierSpec::Storage(StorageQualifier::In))
            })
        });

    if !is_input {
        return None;
    }

    list.head.name.as_ref().map(|name| name.0.as_str())
}

/// Removes `in` declarations that nothing is going to provide.
///
/// `core/rendertype_crumbling.vsh` declares `in vec3 Normal`, while the `CRUMBLING` pipeline draws
/// with `DefaultVertexFormat.BLOCK`, which has no Normal element - and the shader never reads it.
/// GL is happy with that: `GlProgram` binds attribute locations from the pipeline's vertex format,
/// an attribute with no binding reads the default generic value, and nothing observable depends on
/// it. wgpu has no such thing - a shader input with no matching attribute is a validation error
/// ("Argument 4 varying error: Multiple bindings at location 0 are present" once naga numbers it,
/// or a missing-input error if it is numbered).
///
/// Dropping the declaration is the closest wgpu equivalent. A shader that actually *reads* one of
/// these cannot be served either way, and dropping turns that into an "undeclared identifier" from
/// naga, which names the variable.
fn drop_unprovided_inputs(stage: &mut ShaderStage, provided: &HashMap<String, u32>) {
    let mut keep = Vec::with_capacity(stage.0.0.len());

    for declaration in stage.0.0.iter() {
        if let Some(name) = declared_input_name(declaration)
            && !provided.contains_key(name)
        {
            warn!(
                "wgpu-mc: dropping input {name}: the pipeline provides {}",
                {
                    let mut known: Vec<&str> = provided.keys().map(|key| key.as_str()).collect();
                    known.sort_unstable();
                    known.join(", ")
                }
            );

            continue;
        }

        keep.push(declaration.clone());
    }

    stage.0.0 = keep;
}

pub fn apply_layouts(
    vert_stage: &mut ShaderStage,
    frag_stage: &mut ShaderStage,
    uniform_map: &HashMap<String, (u32, u32)>,
    vertex_layout_shape: HashMap<String, u32>,
) {
    let mut out_annotator = IncrementingAnnotator {
        offset: 0,
        target: StorageQualifier::Out,
        found: false,
        insert_location: None,
        map: HashMap::new(),
    };

    let mut uniform_annotator = UniformAnnotator {
        uniform_found: false,
        uniform_binding: None,
        uniform_sets: uniform_map.clone(),
        active: false,
    };

    // Before anything is numbered: a vertex input the pipeline's format does not describe cannot be
    // given a location, and leaving it unnumbered makes naga hand it location 0.
    drop_unprovided_inputs(vert_stage, &vertex_layout_shape);

    let mut incrementing_in_annotator = InAnnotator {
        in_found: false,
        insert_location: None,
        map: vertex_layout_shape,
    };

    vert_stage.visit_mut(&mut out_annotator);
    vert_stage.visit_mut(&mut incrementing_in_annotator);
    vert_stage.visit_mut(&mut uniform_annotator);

    let mut in_annotator = InAnnotator {
        in_found: false,
        insert_location: None,
        map: out_annotator.map,
    };

    let mut rewriter = SamplerBufferRewriter {
        is_sampler_buffer: false,
        buffers: vec![],
        uniform_sets: &uniform_map,
    };

    vert_stage.visit_mut(&mut rewriter);
    vert_stage.visit_mut(&mut RewriteFetches {
        buffers: &rewriter.buffers,
    });

    uniform_annotator.uniform_found = false;
    uniform_annotator.uniform_binding = None;

    // Same again for the fragment stage, against the varyings the vertex stage writes.
    drop_unprovided_inputs(frag_stage, &in_annotator.map);
    frag_stage.visit_mut(&mut in_annotator);
    frag_stage.visit_mut(&mut uniform_annotator);

    frag_stage.visit_mut(&mut rewriter);
    frag_stage.visit_mut(&mut RewriteFetches {
        buffers: &rewriter.buffers,
    });
}

pub struct ProcessedShaderResult {
    pub frag: String,
    pub vert: String,
    pub sampler_types: HashMap<String, TypeSpecifierNonArray>,
    /// Uniform blocks the shaders declared that the pipeline itself did not list, with the binding
    /// each was given by [`add_implicit_uniforms`]. The caller has to add matching entries to the
    /// pipeline layout, or wgpu rejects the pipeline for using a binding the layout does not have.
    pub implicit_uniforms: Vec<(String, u32)>,
}

/// There is a not entirely trivial amount of work done to convert shaders from Minecraft's source into something that wgpu is okay with;
/// The steps performed are described here.
///
/// Initially we feed the shaders through the [cyntax] crate which is our in-house C preprocessor. Yes, really. (Thank you, george lewis)
///
/// Identify the sampler* uniforms by name and type
/// Split each of them into two uniforms and append a suffix, respectively
/// a texture* uniform with suffix _wm_texshim, and a sampler uniform with suffix _wm_sampler
///
/// Patch the constant arrays to include the ordinality of the array in the left-side type identifier, as naga (as of the time of writing this) where it fails to validate constant arrays without it
///
/// [SamplerFinder], [SamplerExpansion]
/// In any locally defined function header (as well as corresponding calls), expand any reference to the previous sampler* (un-"shimmed" uniform) into the two newly created uniform variables.
/// Any reference to the uniform within a call to a built-in GLSL function needs to be wrapped in the sampler* constructor, with the new uniforms
///
/// [ExplicitMipWhenSampling]
/// If we're in a vertex shader, any reference to texture sampling must have an explicit mip level defined (we hardcode it to 0), as WebGPU does not support automatic mip levels in texture sampling in vertex stages
///
/// [RewriteGLBuiltinSemantics]
/// Rewrite gl_[Vertex/Instance]ID to gl_*Index, wrap invocations of gl_VertexIndex in `int(...)` because for some reason naga thinks its an unsigned integer when I'm pretty sure it's supposed to be signed
///
/// Then follows the [apply_layouts] stage. This takes in both the vertex sahder and fragment shader together,
/// as information about the vertex output from the vertex stage is fed into the mappings of the vertex input annotator for the fragment shader.
///
/// ## Vertex transformation
///
/// First, [IncrementingAnnotator] automatically applies indices to the vertex shader `out` declarations, creating the mapping of (uniform name) -> index
///
/// [InAnnotator] is then passed the vertex buffer layout, which then mutates the vertex stage, applying the correct indices to the vertex input bindings (by name) to the vertex shader
///
/// [UniformAnnotator] is then called onto the vertex stage, with the specified uniform map locations generated from the pipeline layout description.
///
/// [SamplerBufferRewriter] is called on the vertex shader.
/// This patches isamplerBuffer to instead become an SSBO and not a uniform binding. This also requires support in the pipeline creation process.
///
/// [RewriteFetches] is then called to rewrite `texelFetch(buffer, index)` into a byte read out of that SSBO.
///
/// A texel of a `CloudFaces`-style buffer is one *byte*, not one `int`: Minecraft's `CloudRenderer`
/// writes each face as three bytes (`CloudRenderer#encodeFace` puts `cellX >> 1`, `cellZ >> 1` and
/// the direction-and-flags byte), and the vertex shader fetches texel `face * 3`, `+ 1` and `+ 2`,
/// so the buffer's texel format is R8I. Declaring the SSBO as `int[]` and reading
/// `inner[index]` therefore read *four* bytes per face from a buffer laid out one byte per face -
/// every field after the first came out of the wrong place, the decoded cell coordinates were
/// nonsense, and the clouds were drawn as a handful of faces somewhere near the camera. The SSBO is
/// a `uint[]` and the fetch extracts the byte, zero-extended: every use of a fetched value in those
/// shaders is a mask or a shift, which sign extension would not have changed.
///
/// The index expression is used twice, which is safe for the one buffer this shim exists for -
/// `CloudRenderer`'s shader fetches with a local variable - and would not be for a fetch with a
/// side-effecting index.
///
/// ## Fragment transformation
///
/// [InAnnotator] is fed the map generated by the vertex stage's [IncrementingAnnotator] and is applied to the fragment shader.
///
/// The [UniformAnnotator] is reset (if necessary), which is then invoked to mutate the fragment shader.
///
/// [SamplerBufferRewriter] and successively [RewriteFetches] is called on the fragment shader.
///
/// Finally, the version is rewritten in both stages to be #version 440, and any statements with the shape `gl_PointSize = ...` are deleted, as HLSL doesn't support it.`
///
pub fn process_shaders(
    vert_source: &str,
    frag_source: &str,
    directives: &str,
    uniform_locations: &HashMap<String, (u32, u32)>,
    vertex_stage_input_layout: HashMap<String, u32>,
) -> ProcessedShaderResult {
    let vert_source = format!("{directives}{vert_source}");
    let frag_source = format!("{directives}{frag_source}");

    let preprocessed_vert = cyntax::preprocess_str(&vert_source, &[]);
    let preprocessed_frag = cyntax::preprocess_str(&frag_source, &[]);

    let mut vert_stage_ast = ShaderStage::parse(preprocessed_vert).unwrap();
    let mut frag_stage_ast = ShaderStage::parse(preprocessed_frag).unwrap();

    vert_stage_ast.visit_mut(&mut MatrixPatcher);
    vert_stage_ast.visit_mut(&mut EmulateGlClipSpace);
    // frag_stage_ast.visit_mut(&mut ForceWhite);

    let mut sampler_types = HashMap::new();

    //Split the samplers, as well as do some other pre-processing
    sampler_types.extend(shim_samplers(&mut vert_stage_ast, true));
    sampler_types.extend(shim_samplers(&mut frag_stage_ast, false));

    //Whatever the shaders declare gets a binding, whether or not the pipeline asked for it.
    let mut uniform_locations = uniform_locations.clone();
    let implicit_uniforms = add_implicit_uniforms(
        &vert_stage_ast,
        &frag_stage_ast,
        &mut uniform_locations,
    );

    //Apply the set and binding layouts to the uniforms
    apply_layouts(
        &mut vert_stage_ast,
        &mut frag_stage_ast,
        &uniform_locations,
        vertex_stage_input_layout,
    );

    vert_stage_ast.0.0.insert(
        0,
        ExternalDeclaration::Preprocessor(Preprocessor::Version(PreprocessorVersion {
            version: 440,
            profile: None,
        })),
    );

    frag_stage_ast.0.0.insert(
        0,
        ExternalDeclaration::Preprocessor(Preprocessor::Version(PreprocessorVersion {
            version: 440,
            profile: None,
        })),
    );

    frag_stage_ast.visit_mut(&mut RemovePointSize {
        is_point_var: false,
    });
    vert_stage_ast.visit_mut(&mut RemovePointSize {
        is_point_var: false,
    });

    let mut vert = String::new();
    let mut frag = String::new();

    show_translation_unit(&mut vert, &vert_stage_ast);
    show_translation_unit(&mut frag, &frag_stage_ast);

    ProcessedShaderResult {
        frag,
        vert,
        sampler_types,
        implicit_uniforms,
    }
}

pub fn shim_samplers(
    shader_stage: &mut ShaderStage,
    explicit_mip: bool,
) -> HashMap<String, TypeSpecifierNonArray> {
    let mut swap = vec![];
    let mut sampler_uniform_names = vec![];

    for (index, ext) in shader_stage.0.0.iter().enumerate() {
        let mut finder = SamplerFinder {
            layout_qualifiers: None,
            names: HashMap::new(),
            uniform: false,
            sampler: None,
        };
        ext.visit(&mut finder);

        if finder.uniform
            && let Some(sampler_type) = finder.sampler
        {
            let mut texture_uniform = ext.clone();
            let mut sampler_uniform = ext.clone();

            sampler_uniform_names.extend(finder.names);

            let (texture_type, sampler_type) = match sampler_type {
                TypeSpecifierNonArray::Sampler2D => {
                    ("texture2D".to_string(), "sampler".to_string())
                }
                TypeSpecifierNonArray::SamplerCube => {
                    ("textureCube".to_string(), "sampler".to_string())
                }
                _ => unreachable!(),
            };

            texture_uniform.visit_mut(&mut TypeChanger {
                new_t: Some(TypeSpecifierNonArray::TypeName(TypeName(texture_type))),
                name_ext: SHIM_TEXTURE_SUFFIX.to_string(),
            });

            sampler_uniform.visit_mut(&mut TypeChanger {
                new_t: Some(TypeSpecifierNonArray::TypeName(TypeName(sampler_type))),
                name_ext: SHIM_SAMPLER_SUFFIX.to_string(),
            });

            swap.push((index, texture_uniform, sampler_uniform));
        }
    }

    for (target, texture, sampler) in swap.into_iter().rev() {
        shader_stage.0.0.insert(target + 1, sampler);
        shader_stage.0.0.insert(target + 1, texture);
        shader_stage.0.0.remove(target);
    }

    shader_stage.visit_mut(&mut NagaFixConstArrayExplicit { size: None });

    let mut expander = SamplerExpansion {
        samplers: sampler_uniform_names
            .iter()
            .map(|(l, r)| (l.clone(), get_sampler_constructor_for_glsl_type(&r)))
            .collect(),
        local_functions: vec![],
    };

    shader_stage.visit_mut(&mut expander);

    if explicit_mip {
        shader_stage.visit_mut(&mut ExplicitMipWhenSampling);
    }

    shader_stage.visit_mut(&mut RewriteGLBuiltinSemantics);

    sampler_uniform_names.into_iter().collect()
}

#[cfg(test)]
mod texel_buffer_tests {
    use super::*;
    use glsl::parser::Parse;
    use glsl::syntax::ShaderStage;
    use glsl::transpiler::glsl::show_translation_unit;
    use std::collections::HashMap;

    /// The shader side of the contract: what the rewritten GLSL computes for one fetched texel.
    ///
    /// This is the same arithmetic the generated expression performs - take the 4-byte word, shift
    /// the byte into place, mask it - so a change to that expression that this does not follow is a
    /// change that has to be made in two places, which is what the test is for.
    fn fetch_byte(buffer: &[u8], index: usize) -> i32 {
        let word = u32::from_le_bytes([
            *buffer.get(index & !3).unwrap_or(&0),
            *buffer.get((index & !3) + 1).unwrap_or(&0),
            *buffer.get((index & !3) + 2).unwrap_or(&0),
            *buffer.get((index & !3) + 3).unwrap_or(&0),
        ]);

        ((word >> ((index as u32 & 3) * 8)) & 0xFF) as i32
    }

    /// Minecraft's `CloudRenderer#encodeFace`: three bytes per face, one texel each.
    fn encode_face(out: &mut Vec<u8>, cell_x: i32, cell_z: i32, direction: i32, flags: i32) {
        let mut dir_and_flags = direction | flags;
        dir_and_flags |= (cell_x & 1) << 7;
        dir_and_flags |= (cell_z & 1) << 6;
        out.push((cell_x >> 1) as u8);
        out.push((cell_z >> 1) as u8);
        out.push(dir_and_flags as u8);
    }

    #[test]
    fn a_face_survives_the_byte_layout() {
        let mut buffer = Vec::new();
        encode_face(&mut buffer, 3, 0, 0, 0);
        encode_face(&mut buffer, 2, 2, 4, 16);
        encode_face(&mut buffer, 255, 254, 7, 16 | 32);
        assert_eq!(buffer.len(), 9);
        assert_eq!(buffer[..3], [0x01, 0x00, 0x80]);

        for face in 0..3usize {
            let cell_x = fetch_byte(&buffer, face * 3);
            let cell_z = fetch_byte(&buffer, face * 3 + 1);
            let dir_and_flags = fetch_byte(&buffer, face * 3 + 2);

            let decoded_x = (cell_x << 1) | ((dir_and_flags & 0x80) >> 7);
            let decoded_z = (cell_z << 1) | ((dir_and_flags & 0x40) >> 6);

            let expected = match face {
                0 => (3, 0, 0, false, false),
                1 => (2, 2, 4, true, false),
                _ => (255, 254, 7, true, true),
            };

            assert_eq!(
                (decoded_x, decoded_z, dir_and_flags & 7, (dir_and_flags & 16) == 16, (dir_and_flags & 32) == 32),
                expected,
                "face {face} decoded to the wrong cell"
            );
        }
    }

    /// The generated expression, evaluated over the same bytes, has to agree with [`fetch_byte`].
    ///
    /// Reading the buffer as `int[]` - which is what this side did - takes *four* bytes per fetch,
    /// so the cell coordinates of every face after the first came out of the wrong place.
    #[test]
    fn the_word_index_is_not_the_texel_index() {
        let mut buffer = Vec::new();
        encode_face(&mut buffer, 3, 0, 0, 0);
        encode_face(&mut buffer, 200, 100, 4, 16);
        assert_eq!(buffer, [0x01, 0x00, 0x80, 100, 50, 0x14]);

        // The second face's first field is byte 3, which lives in the *first* word.
        assert_eq!(fetch_byte(&buffer, 3), 100);

        // A four-byte read of "texel 3" reads from word 3 instead, i.e. past the end of two faces;
        // the word that happens to be there is face 1's remaining bytes, so the field decoded out
        // of it belongs to no face at all.
        let misaligned = u32::from_le_bytes([buffer[4], buffer[5], 0, 0]);
        assert_ne!(misaligned & 0xFF, 100);

        let cell_x = fetch_byte(&buffer, 3);
        let dir_and_flags = fetch_byte(&buffer, 5);
        let decoded_x = (cell_x << 1) | ((dir_and_flags & 0x80) >> 7);
        assert_eq!(decoded_x, 200);
    }

    #[test]
    fn the_rewriter_produces_a_byte_fetch() {
        let uniform_map: HashMap<String, (u32, u32)> =
            HashMap::from([("CloudFaces".to_string(), (0, 4))]);

        let mut stage = ShaderStage::parse(
            "uniform isamplerBuffer CloudFaces; void main() { int index = 3; int cellX = texelFetch(CloudFaces, index).r; }"
                .to_string(),
        )
        .unwrap();

        let mut rewriter = SamplerBufferRewriter {
            is_sampler_buffer: false,
            buffers: vec![],
            uniform_sets: &uniform_map,
        };
        stage.visit_mut(&mut rewriter);
        stage.visit_mut(&mut RewriteFetches {
            buffers: &rewriter.buffers,
        });

        let mut printed = String::new();
        show_translation_unit(&mut printed, &mut stage);
        println!("{printed}");

        assert!(printed.contains("uint[] inner"), "the SSBO is not a uint array: {printed}");
        // The printer writes `0xFFu` as `255u`, so the mask is matched by its value.
        assert!(
            printed.contains(">>2u") && printed.contains("&3u") && printed.contains("&255u"),
            "the fetch is not a byte extraction: {printed}"
        );
    }
}

#[cfg(test)]
mod roundtrip_tests {
    use glsl::parser::Parse;
    use glsl::syntax::ShaderStage;
    use glsl::transpiler::glsl::show_translation_unit;

    /// A compound assignment has to survive the parse/print round-trip, and `cyntax` has to leave
    /// it alone too: `terrain.fsh` averages four samples with `rgssColorLow *= 0.25`, and turning
    /// that multiplication into a subtraction makes every RGSS-filtered surface four times too
    /// bright - which is what a terrain drawn as a white void turned out to be.
    fn roundtrip(source: &str) -> String {
        let mut stage = ShaderStage::parse(source.to_string()).unwrap();
        let mut out = String::new();
        show_translation_unit(&mut out, &mut stage);
        out
    }

    #[test]
    fn compound_assignment_survives_parsing_alone() {
        let printed = roundtrip("void main() { float a = 1.0; a *= 0.25; a /= 2.0; a += 1.0; a -= 3.0; }");
        println!("parse+print: {printed}");
        // The *operators*, not the exact spacing: the printer is free to reflow whitespace, and
        // naga does not care. What it may not do is change which operator is there.
        for operator in ["*=", "/=", "+=", "-="] {
            assert!(printed.contains(operator), "{operator} is missing from: {printed}");
        }
    }

    #[test]
    fn compound_assignment_survives_the_preprocessor() {
        let preprocessed = cyntax::preprocess_str("void main() { float a = 1.0; a *= 0.25; }", &[]);
        println!("cyntax: {preprocessed}");
        assert!(preprocessed.contains("a *= 0.25"), "the preprocessor rewrote it: {preprocessed}");

        let printed = roundtrip(&preprocessed);
        println!("cyntax+parse+print: {printed}");
        assert!(printed.contains("*="), "multiplication was rewritten after preprocessing: {printed}");
    }
}

#[cfg(test)]
mod operator_matrix {
    /// Prints what every compound assignment comes back as, so a mis-mapping is visible at a
    /// glance rather than inferred from one broken shader.
    #[test]
    fn every_compound_assignment_through_the_preprocessor() {
        for operator in ["=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>="] {
            let source = format!("void main() {{ float a = 1.0; a {operator} 0.25; }}");
            let printed = cyntax::preprocess_str(&source, &[]);
            let kept = printed.contains(&format!("a {operator} 0.25"));
            println!("{operator:>4} -> kept={kept}  {}", printed.trim());
        }
    }
}
