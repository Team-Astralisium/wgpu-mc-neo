package dev.birb.wgpu.backend

import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.platform.DestFactor
import com.mojang.blaze3d.platform.SourceFactor
import com.mojang.blaze3d.shaders.ShaderSource
import com.mojang.blaze3d.shaders.ShaderType
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.VertexFormat
import dev.birb.wgpu.rust.NativeNames
import dev.birb.wgpu.rust.WmNative
import net.minecraft.client.renderer.ShaderDefines
import net.minecraft.resources.Identifier
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap

/**
 * A Blaze3D [RenderPipeline] compiled into a native `wgpu::RenderPipeline`.
 *
 * A pipeline needs one native pipeline per depth configuration: Rust reads the depth state from the
 * presence of that struct field, and a render pass knows at bind time whether it has a depth
 * attachment ([forDepth]). Both are never needed at once - a GUI pipeline is drawn in a pass
 * without depth, a world pipeline in one with - so the variant that was asked for first is compiled
 * once, and the other is written the first time something draws with it ([createVariant]). Minecraft
 * precompiles every pipeline it ships with `precompilePipeline`, which is where the first variant
 * comes from.
 *
 * The whole `RenderPipeline` C struct is assembled inside one confined [Arena]; the struct writes
 * use the named offsets from [WmNative] so the ABI mapping can be checked against `bindings.h`
 * line by line.
 *
 * Note: 26.1 removed the per-pipeline colour target *format* (only blend state remains in
 * `ColorTargetState`) and the main render target is always `RGBA8`, which is what is declared
 * here. A pipeline that ever needs another target format would change [MAIN_TARGET_FORMAT].
 */
class WgpuCompiledRenderPipeline private constructor(
    private val device: WgpuDevice,
    private val pipeline: RenderPipeline,
    /** The variant compiled so far, and whether it is the one with depth state. */
    first: MemorySegment,
    firstHasDepth: Boolean,
) : CompiledRenderPipeline, AutoCloseable {

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The two native pipelines, one of which may not exist yet.
     *
     * Guarded by the lock because a pipeline can be bound from more than one place in a frame - a
     * pass that draws the same pipeline twice with different depth configurations would otherwise
     * compile it twice - and the compile is not cheap.
     */
    private val lock = Any()
    private var withDepth: MemorySegment? = if (firstHasDepth) first else null
    private var withoutDepth: MemorySegment? = if (firstHasDepth) null else first

    override fun isValid(): Boolean = true

    /**
     * The native pipeline to draw with in a pass that has a depth attachment or does not.
     *
     * Creates the variant the first time it is asked for, which is the whole point: a pipeline that
     * is only ever drawn in one kind of pass never pays for the other one's shader modules, layouts
     * and driver compilation.
     */
    fun forDepth(wantsDepth: Boolean): MemorySegment {
        val existing = if (wantsDepth) withDepth else withoutDepth
        if (existing != null) {
            return existing
        }

        return createVariant(wantsDepth)
    }

    /**
     * Builds the missing variant from the one that exists.
     *
     * The depth state is the only difference, so the native side is asked for a pipeline built from
     * the other one's shader modules and layouts rather than recompiled from the descriptor: that
     * is what makes the second variant cost a driver pipeline creation and nothing else.
     */
    private fun createVariant(wantsDepth: Boolean): MemorySegment = synchronized(lock) {
        val existing = if (wantsDepth) withDepth else withoutDepth
        if (existing != null) {
            return existing
        }

        val source = withDepth ?: withoutDepth
            ?: throw IllegalStateException("wgpu: ${pipeline.location} has no compiled variant to build from")

        val segment = Arena.ofConfined().use { arena ->
            val depthState = if (wantsDepth) depthStencilState(arena) else MemorySegment.NULL

            WmNative.createPipelineVariant.invokeExact(
                device.renderer,
                source,
                depthState,
            ) as MemorySegment
        }

        if (wantsDepth) withDepth = segment else withoutDepth = segment
        segment
    }

    /**
     * The depth state a pass with a depth attachment needs.
     *
     * The pipeline's own depth test, not a constant: `Always` with depth writes on is not a depth
     * test at all, and Minecraft asks for `LESS_THAN_OR_EQUAL` on almost everything, `EQUAL` with
     * writes off for glint, and biased variants for overlays.
     *
     * A pipeline with *no* depth state at all - every GUI pipeline, including the ones that draw
     * the title screen over the panorama - means the depth test is off, which OpenGL expresses by
     * disabling it. wgpu will not accept a pipeline without a depth-stencil state in a pass that
     * has a depth attachment, so "off" has to be spelt as a test that never rejects and a write
     * that never happens. Anything else silently depth-tests the GUI against whatever the previous
     * pass left behind, which is how a fully drawn title screen ended up presented as its bare
     * panorama.
     */
    private fun depthStencilState(arena: Arena): MemorySegment {
        val depth = pipeline.depthStencilState

        val state = arena.allocate(WmNative.DEPTH_STENCIL_STATE)
        state.set(
            WmNative.LONG,
            WmNative.DEPTH_STENCIL_COMPARE,
            depth?.let { compareOp(it.depthTest()) } ?: WmNative.COMPARE_ALWAYS,
        )
        state.set(
            WmNative.LONG,
            WmNative.DEPTH_STENCIL_ACTIVE,
            if (depth?.writeDepth() == true) 1L else 0L,
        )
        state.set(
            WmNative.INT,
            WmNative.DEPTH_STENCIL_BIAS_CONSTANT,
            (depth?.depthBiasConstant() ?: 0.0f).toInt(),
        )
        state.set(
            WmNative.FLOAT,
            WmNative.DEPTH_STENCIL_BIAS_SLOPE_SCALE,
            depth?.depthBiasScaleFactor() ?: 0.0f,
        )
        return state
    }

    /**
     * The plan's binding slots, read out of the native pipeline once.
     *
     * Both depth variants are built from the same plan, so one table serves them both. `null` when
     * the plan is wider than the ABI's binding array, which `bindingsOf` already logged.
     */
    val planBindings: PlanBindings? by lazy {
        readPlanBindings(withDepth ?: withoutDepth ?: MemorySegment.NULL)
    }

    /** [planBindings] for the Java side, which reads it once when a pass binds this pipeline. */
    @JvmName("slotsForJava")
    fun javaPlanBindings(): PlanBindings? = planBindings

    /**
     * Frees whichever native pipelines exist, once.
     *
     * Minecraft's `CompiledRenderPipeline` has one method and is not `AutoCloseable`, so nothing
     * calls this on its own; [clearCaches] does, because that is the point Minecraft itself decides
     * a pipeline is finished with. The cleaner registered below is the backstop for the pipelines
     * that are never cleared - a compiled pipeline dropped to the garbage collector otherwise takes
     * two `wgpu::RenderPipeline`s, their bind group layouts and a copy of the whole descriptor with
     * it, and nothing on the native side ever hears about it.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        val with = synchronized(lock) {
            val slots = arrayOf(withDepth, withoutDepth)
            withDepth = null
            withoutDepth = null
            slots
        }

        for (variant in with) {
            // Null for a variant that was never asked for, which the native side ignores.
            WmNative.dropRenderPipeline.invokeExact(variant ?: MemorySegment.NULL) as Unit
        }
    }

    init {
        CLEANER.register(this, PipelineReleaser(this, closed))
    }

    companion object {

        private val pipelines = ConcurrentHashMap<RenderPipeline, WgpuCompiledRenderPipeline>()

        /** Compiled shader sources keyed by (id, stage, defines): shader lookup is not cheap. */
        private val shaderSources = ConcurrentHashMap<ShaderKey, String>()

        /** Pipelines already described in the log, so each one is reported exactly once. */
        private val described = ConcurrentHashMap.newKeySet<Identifier>()

        /**
         * Says what a pipeline asks for, once per pipeline.
         *
         * A frame whose geometry is all rejected - every pixel keeps the clear colour - is almost
         * always a state translation that disagrees with what Minecraft asked for, and the only way
         * to tell which one is to print both sides. Diagnostics.
         */
        private fun describeOnce(pipeline: RenderPipeline) {
            if (!Diagnostics.isEnabled()) return
            if (!described.add(pipeline.location)) return

            val target = pipeline.colorTargetState
            val blend = target.blendFunction().orElse(null)
            val depth = pipeline.depthStencilState

            dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                "wgpu: pipeline {}: vertex {}, mode {}, uniforms {}, samplers {}, " +
                    // `{:#x}` is not slf4j syntax: it reads the value as the eighth argument of a
                    // seven-placeholder pattern and logs a warning about it instead of the mask.
                    "depth {}, blend {}, write mask {}, cull {}",
                pipeline.location,
                pipeline.vertexFormat?.let { format ->
                    "${format.vertexSize}B[${format.elements.joinToString(",") { format.getElementName(it) }}]"
                } ?: "none",
                pipeline.vertexFormatMode,
                pipeline.uniforms.joinToString(",") { it.name() },
                pipeline.samplers.joinToString(","),
                depth?.let { "${it.depthTest()} write=${it.writeDepth()} bias=${it.depthBiasConstant()}/${it.depthBiasScaleFactor()}" }
                    ?: "none",
                blend?.let { "${it.sourceColor()}/${it.destColor()} a ${it.sourceAlpha()}/${it.destAlpha()}" }
                    ?: "none",
                String.format("0x%x", target.writeMask()),
                pipeline.isCull(),
            )
        }

        private data class ShaderKey(val id: Identifier, val type: ShaderType, val defines: ShaderDefines)

        @JvmStatic
        fun of(
            device: WgpuDevice,
            pipeline: RenderPipeline,
            shaderSource: ShaderSource,
            wantsDepth: Boolean,
        ): WgpuCompiledRenderPipeline =
            pipelines.computeIfAbsent(pipeline) { compile(device, it, shaderSource, wantsDepth) }

        fun clearCaches() {
            // The native pipelines go with the cache entries. Dropping the objects alone leaked two
            // per pipeline per resource reload, which is what an empty-bodied cache clear did.
            pipelines.values.forEach { it.close() }
            pipelines.clear()
            shaderSources.clear()
        }

        /** Releases compiled pipelines the garbage collector takes before anything clears them. */
        val CLEANER: java.lang.ref.Cleaner = java.lang.ref.Cleaner.create()

        /**
         * `ShaderSource#get` may return null for a shader that failed to load. Rust needs a string
         * either way, so a null becomes the empty source and Minecraft's own shader diagnostics
         * report the failure.
         */
        private fun sourceFor(
            id: Identifier,
            type: ShaderType,
            defines: ShaderDefines,
            source: ShaderSource,
        ): String = shaderSources.computeIfAbsent(ShaderKey(id, type, defines)) {
            source.get(it.id, it.type) ?: ""
        }

        /**
         * The compiled pipeline for [pipeline], compiling the variant [wantsDepth] asks for.
         *
         * `wantsDepth` is what the pass that is binding it has: a pass with a depth attachment
         * cannot use a pipeline without a depth-stencil state, and one without cannot use a pipeline
         * that has one. The other variant is written later, out of this one, the first time a pass
         * of the other kind draws with it.
         */
        private fun compile(
            device: WgpuDevice,
            pipeline: RenderPipeline,
            shaderSource: ShaderSource,
            wantsDepth: Boolean,
        ): WgpuCompiledRenderPipeline = Arena.ofConfined().use { arena ->
            describeOnce(pipeline)

            val descriptor = arena.allocate(WmNative.RENDER_PIPELINE)

            val bindGroupLayouts = bindGroupLayouts(arena, pipeline)
            val vertexFormats = vertexFormats(arena, pipeline.vertexFormat)
            val colorTargets = colorTargets(arena, pipeline)
            val depthState = if (wantsDepth) depthStencilState(arena, pipeline) else MemorySegment.NULL

            descriptor.set(WmNative.ADDRESS, WmNative.PIPELINE_NAME, NativeNames.utf8(pipeline.location.toString()))
            descriptor.set(WmNative.ADDRESS, WmNative.PIPELINE_BIND_GROUP_LAYOUTS, bindGroupLayouts)
            descriptor.set(WmNative.ADDRESS, WmNative.PIPELINE_COLOR_TARGETS, colorTargets)
            descriptor.set(WmNative.ADDRESS, WmNative.PIPELINE_DEPTH_STENCIL, depthState)
            descriptor.set(WmNative.ADDRESS, WmNative.PIPELINE_VERTEX_FORMATS, vertexFormats)
            descriptor.set(
                WmNative.ADDRESS,
                WmNative.PIPELINE_VERTEX_SHADER,
                arena.allocateFrom(
                    sourceFor(pipeline.vertexShader, ShaderType.VERTEX, pipeline.shaderDefines, shaderSource)
                ),
            )
            descriptor.set(
                WmNative.ADDRESS,
                WmNative.PIPELINE_FRAGMENT_SHADER,
                arena.allocateFrom(
                    sourceFor(pipeline.fragmentShader, ShaderType.FRAGMENT, pipeline.shaderDefines, shaderSource)
                ),
            )
            descriptor.set(
                WmNative.ADDRESS,
                WmNative.PIPELINE_DIRECTIVES,
                arena.allocateFrom(pipeline.shaderDefines.asSourceDirectives()),
            )
            descriptor.set(WmNative.ADDRESS, WmNative.PIPELINE_FRAG_STATE, MemorySegment.NULL)
            descriptor.set(
                WmNative.LONG,
                WmNative.PIPELINE_TOPOLOGY,
                pipeline.vertexFormatMode.toNativeTopology(),
            )
            // `RenderPipeline#isCull` is what OpenGL's `glEnable(GL_CULL_FACE)` answered to, and it
            // defaults to true. Without it every quad was rasterized front and back: on a blended
            // render type both copies land at the same depth, so the one that shows is whichever
            // the depth test lets through, which is what made leaves ghost and flicker.
            descriptor.set(
                WmNative.LONG,
                WmNative.PIPELINE_CULL,
                if (pipeline.isCull()) 1L else 0L,
            )

            // One variant, for the call that asked for it. Minecraft precompiles every pipeline it
            // ships and only ever draws with one depth configuration per pipeline, so the other
            // variant is built on first use instead of here - see `createVariant`.
            val segment = WmNative.compileRenderPipeline.invokeExact(device.renderer, descriptor) as MemorySegment

            WgpuCompiledRenderPipeline(device, pipeline, segment, wantsDepth)
        }

        /**
         * The `BlazeDepthStencilState` for a pass with a depth attachment.
         *
         * Both of these used to be constants - `Always` with writes forced on - which is not a depth
         * test at all. Minecraft asks for `LESS_THAN_OR_EQUAL` on almost everything, `EQUAL` with
         * writes off for glint, and biased variants for the overlays that sit on top of the block
         * they belong to.
         *
         * A pipeline with *no* depth state at all - every GUI pipeline, including the ones that draw
         * the title screen over the panorama - means the depth test is off, which OpenGL expresses by
         * disabling it. wgpu will not accept a pipeline without a depth-stencil state in a pass that
         * has a depth attachment, so "off" has to be spelt as a test that never rejects and a write
         * that never happens. Anything else silently depth-tests the GUI against whatever the
         * previous pass left behind, which is how a fully drawn title screen ended up presented as
         * its bare panorama.
         */
        private fun depthStencilState(arena: Arena, pipeline: RenderPipeline): MemorySegment {
            val depth = pipeline.depthStencilState

            val state = arena.allocate(WmNative.DEPTH_STENCIL_STATE)
            state.set(
                WmNative.LONG,
                WmNative.DEPTH_STENCIL_COMPARE,
                depth?.let { compareOp(it.depthTest()) } ?: WmNative.COMPARE_ALWAYS,
            )
            state.set(
                WmNative.LONG,
                WmNative.DEPTH_STENCIL_ACTIVE,
                if (depth?.writeDepth() == true) 1L else 0L,
            )
            state.set(
                WmNative.INT,
                WmNative.DEPTH_STENCIL_BIAS_CONSTANT,
                (depth?.depthBiasConstant() ?: 0.0f).toInt(),
            )
            state.set(
                WmNative.FLOAT,
                WmNative.DEPTH_STENCIL_BIAS_SLOPE_SCALE,
                depth?.depthBiasScaleFactor() ?: 0.0f,
            )
            return state
        }

        /**
         * `RawArray_BlazeColorTargetState` holding one `BlazeColorTargetState`.
         *
         * The target *format* is ours to supply - 26.1 removed it from `ColorTargetState` and the
         * main render target is always `RGBA8` - but the blend function and the write mask come
         * straight from the pipeline, and they matter: `OVERLAY` on the GUI background,
         * `INVERT` on `gui_invert`, `ADDITIVE`, premultiplied alpha, and pipelines that write only
         * the colour channels or none of them.
         */
        private fun colorTargets(arena: Arena, pipeline: RenderPipeline): MemorySegment {
            val target = pipeline.colorTargetState
            val state = arena.allocate(WmNative.COLOR_TARGET_STATE)

            state.set(WmNative.LONG, WmNative.COLOR_TARGET_FORMAT, MAIN_TARGET_FORMAT)
            // INT, not LONG: `write_mask` is a `u32` in the struct and a LONG write would run four
            // bytes past the end of it.
            state.set(WmNative.INT, WmNative.COLOR_TARGET_WRITE_MASK, target.writeMask())
            state.set(WmNative.ADDRESS, WmNative.COLOR_TARGET_BLEND, blendState(arena, target))

            val array = arena.allocate(WmNative.RAW_ARRAY)
            WmNative.writeRawArray(array, 0L, state, 1L)
            return array
        }

        /**
         * The `BlazeBlendState` a target points at, or null when the pipeline wants no blending.
         *
         * `BlendFunction` is a record of four `glBlendFuncSeparate` factors, which is exactly the
         * shape of wgpu's `BlendState`; the operation is always an add.
         */
        private fun blendState(arena: Arena, target: ColorTargetState): MemorySegment {
            val blend = target.blendFunction().orElse(null) ?: return MemorySegment.NULL

            val state = arena.allocate(WmNative.BLEND_STATE)
            state.set(WmNative.LONG, WmNative.BLEND_STATE_SRC_COLOR, sourceFactor(blend.sourceColor()))
            state.set(WmNative.LONG, WmNative.BLEND_STATE_DST_COLOR, destFactor(blend.destColor()))
            state.set(WmNative.LONG, WmNative.BLEND_STATE_SRC_ALPHA, sourceFactor(blend.sourceAlpha()))
            state.set(WmNative.LONG, WmNative.BLEND_STATE_DST_ALPHA, destFactor(blend.destAlpha()))
            return state
        }

        private fun sourceFactor(factor: SourceFactor): Long = when (factor) {
            SourceFactor.ZERO -> WmNative.BLEND_ZERO
            SourceFactor.ONE -> WmNative.BLEND_ONE
            SourceFactor.SRC_COLOR -> WmNative.BLEND_SRC_COLOR
            SourceFactor.ONE_MINUS_SRC_COLOR -> WmNative.BLEND_ONE_MINUS_SRC_COLOR
            SourceFactor.DST_COLOR -> WmNative.BLEND_DST_COLOR
            SourceFactor.ONE_MINUS_DST_COLOR -> WmNative.BLEND_ONE_MINUS_DST_COLOR
            SourceFactor.SRC_ALPHA -> WmNative.BLEND_SRC_ALPHA
            SourceFactor.ONE_MINUS_SRC_ALPHA -> WmNative.BLEND_ONE_MINUS_SRC_ALPHA
            SourceFactor.DST_ALPHA -> WmNative.BLEND_DST_ALPHA
            SourceFactor.ONE_MINUS_DST_ALPHA -> WmNative.BLEND_ONE_MINUS_DST_ALPHA
            SourceFactor.SRC_ALPHA_SATURATE -> WmNative.BLEND_SRC_ALPHA_SATURATE
            // wgpu carries a single constant blend colour where GL has separate colour and alpha
            // ones, so the two collapse onto the same factor. No Minecraft pipeline asks for either.
            SourceFactor.CONSTANT_ALPHA, SourceFactor.CONSTANT_COLOR -> WmNative.BLEND_CONSTANT
            SourceFactor.ONE_MINUS_CONSTANT_ALPHA, SourceFactor.ONE_MINUS_CONSTANT_COLOR ->
                WmNative.BLEND_ONE_MINUS_CONSTANT
        }

        private fun destFactor(factor: DestFactor): Long = when (factor) {
            DestFactor.ZERO -> WmNative.BLEND_ZERO
            DestFactor.ONE -> WmNative.BLEND_ONE
            DestFactor.SRC_COLOR -> WmNative.BLEND_SRC_COLOR
            DestFactor.ONE_MINUS_SRC_COLOR -> WmNative.BLEND_ONE_MINUS_SRC_COLOR
            DestFactor.DST_COLOR -> WmNative.BLEND_DST_COLOR
            DestFactor.ONE_MINUS_DST_COLOR -> WmNative.BLEND_ONE_MINUS_DST_COLOR
            DestFactor.SRC_ALPHA -> WmNative.BLEND_SRC_ALPHA
            DestFactor.ONE_MINUS_SRC_ALPHA -> WmNative.BLEND_ONE_MINUS_SRC_ALPHA
            DestFactor.DST_ALPHA -> WmNative.BLEND_DST_ALPHA
            DestFactor.ONE_MINUS_DST_ALPHA -> WmNative.BLEND_ONE_MINUS_DST_ALPHA
            DestFactor.CONSTANT_ALPHA, DestFactor.CONSTANT_COLOR -> WmNative.BLEND_CONSTANT
            DestFactor.ONE_MINUS_CONSTANT_ALPHA, DestFactor.ONE_MINUS_CONSTANT_COLOR ->
                WmNative.BLEND_ONE_MINUS_CONSTANT
        }

        /** `RawArray_VertexFormat` holding the pipeline's single vertex format. */
        private fun vertexFormats(arena: Arena, format: VertexFormat?): MemorySegment {
            val array = arena.allocate(WmNative.RAW_ARRAY)
            if (format == null) {
                WmNative.writeRawArray(array, 0L, MemorySegment.NULL, 0L)
                return array
            }

            val elements = format.elements
            val nativeElements = arena.allocate(WmNative.VERTEX_FORMAT_ELEMENT, elements.size.toLong())

            for ((index, element) in elements.withIndex()) {
                val base = WmNative.elementOffset(WmNative.VERTEX_FORMAT_ELEMENT, index)
                nativeElements.set(
                    WmNative.LONG,
                    base + WmNative.VERTEX_FORMAT_ELEMENT_OFFSET,
                    format.getOffset(element).toLong(),
                )
                nativeElements.set(
                    WmNative.LONG,
                    base + WmNative.VERTEX_FORMAT_ELEMENT_FORMAT,
                    WgpuFormat.nativeId(element),
                )
                nativeElements.set(
                    WmNative.ADDRESS,
                    base + WmNative.VERTEX_FORMAT_ELEMENT_NAME,
                    NativeNames.utf8(format.getElementName(element)),
                )
            }

            val elementArray = arena.allocate(WmNative.RAW_ARRAY)
            WmNative.writeRawArray(elementArray, 0L, nativeElements, elements.size.toLong())

            val nativeFormat = arena.allocate(WmNative.VERTEX_FORMAT)
            nativeFormat.set(WmNative.ADDRESS, WmNative.VERTEX_FORMAT_ELEMENTS, elementArray)
            nativeFormat.set(WmNative.LONG, WmNative.VERTEX_FORMAT_VERTEX_SIZE, format.vertexSize.toLong())

            WmNative.writeRawArray(array, 0L, nativeFormat, 1L)
            return array
        }

        /**
         * 26.1 flattens a pipeline's bindings into `samplers` and `uniforms`; Rust wants a single
         * entry array per bind group with uniforms first, which is the order the shader binding
         * indices assume.
         */
        private fun bindGroupLayouts(arena: Arena, pipeline: RenderPipeline): MemorySegment {
            val uniforms = pipeline.uniforms
            val samplers = pipeline.samplers
            val entryCount = uniforms.size + samplers.size
            val entries = arena.allocate(WmNative.BIND_GROUP_ENTRY, entryCount.toLong())

            fun writeEntry(index: Int, kind: Long, name: String, format: Long) {
                val base = WmNative.elementOffset(WmNative.BIND_GROUP_ENTRY, index)
                entries.set(WmNative.LONG, base + WmNative.BIND_GROUP_ENTRY_TYPE, kind)
                entries.set(WmNative.ADDRESS, base + WmNative.BIND_GROUP_ENTRY_NAME, NativeNames.utf8(name))
                entries.set(WmNative.LONG, base + WmNative.BIND_GROUP_ENTRY_FORMAT, format)
            }

            for ((index, uniform) in uniforms.withIndex()) {
                writeEntry(
                    index,
                    if (uniform.type() == UniformType.TEXEL_BUFFER) WmNative.ENTRY_TEXEL_BUFFER
                    else WmNative.ENTRY_UNIFORM_BUFFER,
                    uniform.name(),
                    uniform.textureFormat()?.let { WgpuFormat.nativeId(it) } ?: WmNative.NONE,
                )
            }

            for ((index, sampler) in samplers.withIndex()) {
                writeEntry(uniforms.size + index, WmNative.ENTRY_SAMPLER, sampler, WmNative.NONE)
            }

            val entryArray = arena.allocate(WmNative.RAW_ARRAY)
            WmNative.writeRawArray(entryArray, 0L, entries, entryCount.toLong())

            // struct BlazeBindGroupLayout { RawArray_BindGroupEntryDescriptor *entries; }
            val layout = arena.allocate(WmNative.BIND_GROUP_LAYOUT)
            layout.set(WmNative.ADDRESS, 0L, entryArray)

            val layouts = arena.allocate(WmNative.RAW_ARRAY)
            WmNative.writeRawArray(layouts, 0L, layout, 1L)
            return layouts
        }

        private fun VertexFormat.Mode.toNativeTopology(): Long = when (this) {
            VertexFormat.Mode.LINES -> WmNative.TOPOLOGY_LINES
            VertexFormat.Mode.DEBUG_LINES -> WmNative.TOPOLOGY_LINES
            VertexFormat.Mode.DEBUG_LINE_STRIP -> WmNative.TOPOLOGY_DEBUG_LINE_STRIP
            VertexFormat.Mode.POINTS -> WmNative.TOPOLOGY_POINTS
            VertexFormat.Mode.TRIANGLES -> WmNative.TOPOLOGY_TRIANGLES
            VertexFormat.Mode.TRIANGLE_STRIP -> WmNative.TOPOLOGY_TRIANGLE_STRIP
            VertexFormat.Mode.TRIANGLE_FAN -> WmNative.TOPOLOGY_TRIANGLE_FAN
            VertexFormat.Mode.QUADS -> WmNative.TOPOLOGY_QUADS
        }

        /** Main render targets are always RGBA8 in 26.1. */
        private val MAIN_TARGET_FORMAT = WmNative.RGBA8_UNORM

        /**
         * `CompareOp` as the native `CompareFunction` wgpu wants.
         *
         * The numbering is this ABI's own (see `WmNative`), so the two sides only have to agree
         * with each other rather than with any GL or D3D constant.
         */
        private fun compareOp(op: CompareOp): Long = when (op) {
            CompareOp.NEVER_PASS -> WmNative.COMPARE_NEVER
            CompareOp.LESS_THAN -> WmNative.COMPARE_LESS
            CompareOp.LESS_THAN_OR_EQUAL -> WmNative.COMPARE_LESS_EQUAL
            CompareOp.EQUAL -> WmNative.COMPARE_EQUAL
            CompareOp.NOT_EQUAL -> WmNative.COMPARE_NOT_EQUAL
            CompareOp.GREATER_THAN_OR_EQUAL -> WmNative.COMPARE_GREATER_EQUAL
            CompareOp.GREATER_THAN -> WmNative.COMPARE_GREATER
            CompareOp.ALWAYS_PASS -> WmNative.COMPARE_ALWAYS
        }
    }
}

/**
 * Frees a collected pipeline's native handles, on the cleaner's thread.
 *
 * A separate class holding the pipeline itself rather than its two pointers: the variants are
 * compiled lazily, so which of them exist is only known at the moment they are freed, and the
 * pipeline is unreachable from the collector's side either way - what the object must not capture
 * is anything that keeps the *pipeline* alive, and it held nothing else to begin with.
 */
private class PipelineReleaser(
    private val compiled: WgpuCompiledRenderPipeline,
    private val closed: java.util.concurrent.atomic.AtomicBoolean,
) : Runnable {

    override fun run() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        try {
            compiled.close()
        } catch (error: Throwable) {
            dev.birb.wgpu.WgpuMcMod.LOGGER.warn("wgpu: could not free a compiled pipeline", error)
        }
    }
}

/**
 * A Pipeline's bindings, resolved to slots once.
 *
 * The slot of a binding is its index in the plan, which is what `DrawCall`'s binding array is
 * indexed by and what `draw_call` walks on the native side. This is the table that maps the
 * *names* Minecraft binds under onto those slots, once per compiled pipeline, instead of a
 * name lookup per binding per draw.
 */
class PlanBindings internal constructor(
    /** Slot `i`: the `WmNative.DRAW_BINDING_*` kind the plan expects there. */
    @JvmField val kinds: IntArray,
) {
    private val slots = HashMap<String, IntArray>()

    internal fun put(name: String, slots: IntArray) {
        this.slots[name] = slots
    }

    internal fun putIfAbsentName(name: String, slots: IntArray) {
        this.slots.putIfAbsent(name, slots)
    }

    /** The slots a binding name goes in: one for a uniform, two for a combined sampler. */
    fun of(name: String): IntArray? = slots[name]

    val count: Int get() = kinds.size
}

/**
 * Reads the plan's binding table out of the native pipeline.
 *
 * `pipeline_bindings` fills the caller's array and returns how many entries it wrote, or 0
 * when the plan has more bindings than the array holds - which is a hard failure rather than
 * a truncated table, because a draw with bindings in the wrong slots renders nonsense.
 */
internal fun readPlanBindings(pipeline: MemorySegment): PlanBindings? =
    Arena.ofConfined().use { arena ->
        val entries = arena.allocate(WmNative.PLAN_BINDING, WmNative.MAX_DRAW_BINDINGS.toLong())
        val array = arena.allocate(WmNative.RAW_ARRAY)
        WmNative.writeRawArray(array, 0L, entries, WmNative.MAX_DRAW_BINDINGS.toLong())

        val count = WmNative.pipelineBindings.invokeExact(pipeline, array) as Int
        if (count <= 0) {
            dev.birb.wgpu.WgpuMcMod.LOGGER.error(
                "wgpu: a pipeline has more than {} bindings, which this backend cannot draw",
                WmNative.MAX_DRAW_BINDINGS,
            )
            return null
        }

        val kinds = IntArray(count)
        val table = PlanBindings(kinds)
        val textures = HashMap<String, Int>()
        val samplers = HashMap<String, Int>()

        for (slot in 0 until count) {
            val base = WmNative.elementOffset(WmNative.PLAN_BINDING, slot)
            val name = arena.readString(entries, base + WmNative.PLAN_BINDING_NAME)
            val declared = arena.readString(entries, base + WmNative.PLAN_BINDING_DECLARED_NAME)
            val kind = entries.get(WmNative.INT, base + WmNative.PLAN_BINDING_KIND)

            kinds[slot] = kind

            when (kind) {
                WmNative.DRAW_BINDING_TEXTURE -> textures[declared] = slot
                WmNative.DRAW_BINDING_SAMPLER -> samplers[declared] = slot
                // A uniform or a texel buffer: one slot, bound by the name the pipeline
                // declared. A texture's own slot is only reachable through the pair below.
                else -> table.put(declared, intArrayOf(slot))
            }

            // The shim name is worth keeping too: a caller that binds under it directly
            // (there is none today) would otherwise silently find nothing.
            if (kind != WmNative.DRAW_BINDING_TEXTURE) {
                table.putIfAbsentName(name, intArrayOf(slot))
            }
        }

        for ((declared, textureSlot) in textures) {
            val samplerSlot = samplers[declared] ?: continue
            table.put(declared, intArrayOf(textureSlot, samplerSlot))
        }

        table
    }

/** Reads the `char *` a `PlanBinding` holds at [offset] as a Kotlin string. */
private fun Arena.readString(entries: MemorySegment, offset: Long): String {
    val pointer = entries.get(WmNative.PTR, offset) as MemorySegment
    return pointer.reinterpret(256L).getString(0L)
}



