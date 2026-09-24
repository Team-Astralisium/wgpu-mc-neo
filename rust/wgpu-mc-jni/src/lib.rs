#![feature(debug_closure_helpers)]
#![feature(ptr_metadata)]
pub extern crate wgpu_mc;

use arc_swap::access::Access;
use arc_swap::{ArcSwap, ArcSwapAny};
use byteorder::{LittleEndian, ReadBytesExt};
use core::slice;
use crossbeam_channel::{Receiver, Sender, unbounded};
use glam::{IVec3, Mat4, ivec2, ivec3};
use jni::objects::{
    AutoElements, GlobalRef, JByteArray, JClass, JIntArray, JLongArray, JObject, JObjectArray,
    JPrimitiveArray, JString, JValue, JValueOwned, ReleaseMode, WeakRef,
};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyte, jint, jsize, jstring};
use jni::{JNIEnv, JavaVM};
use jni_fn::jni_fn;
use once_cell::sync::{Lazy, OnceCell};
use palette::PALETTE_STORAGE;
use parking_lot::{Mutex, RwLock};
use pia::PIA_STORAGE;
use rayon::{ThreadPool, ThreadPoolBuilder};
use renderer::MATRICES;
use std::cell::RefCell;
use std::collections::HashMap;
use std::fmt::Debug;
use std::io::{Cursor, Write, stdout};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Instant;
use std::{mem, thread};
use wgpu::Extent3d;
use wgpu_mc::render::graph::{Geometry, RenderGraph, ResourceBacking};
use wgpu_mc::wgpu::util::DeviceExt;

use wgpu_mc::mc::block::{BlockstateKey, ChunkBlockState};
use wgpu_mc::mc::chunk::{BlockStateProvider, LightLevel, bake_section};
use wgpu_mc::mc::resource::{ResourcePath, ResourceProvider};
use wgpu_mc::mc::SkyState;
use wgpu_mc::minecraft_assets::schemas::blockstates::multipart::StateValue;
use wgpu_mc::render::pipeline::BLOCK_ATLAS;
use wgpu_mc::texture::{BindableTexture, TextureAndView};
use wgpu_mc::wgpu::{self, CurrentSurfaceTexture, TextureFormat};
use wgpu_mc::{Frustum, WmRenderer};

use crate::lighting::DeserializedLightData;
use crate::palette::JavaPalette;
use crate::pia::PackedIntegerArray;
use crate::settings::Settings;

mod alloc;
mod application;
pub mod blaze;
mod debug;
mod device;
pub mod entity;
mod gl;
mod lighting;
mod palette;
mod pia;
pub mod preprocessing;
mod renderer;
mod settings;

/// Checks that the JVM side of the two bridges still matches this crate: the JNI declarations in
/// `WgpuNative.kt`, the hand-written C-ABI bindings in `WmNative.kt`, and the struct layouts and
/// enum numbers `bindings.h` describes. Nothing else checks those, and each of them fails at
/// runtime rather than at compile time.
#[cfg(test)]
mod abi_tests;

#[derive(Debug)]
struct MinecraftRenderState {
    //draw_queue: Vec<>,
    _render_world: bool,
}

#[allow(dead_code)]
struct MouseState {
    pub x: f64,
    pub y: f64,
}

// static ENTITIES: OnceCell<HashMap<>> = OnceCell::new();
static RENDERER: OnceCell<WmRenderer> = OnceCell::new();

pub static RENDER_GRAPH: OnceCell<Mutex<RenderGraph>> = OnceCell::new();
pub static CUSTOM_GEOMETRY: OnceCell<Mutex<HashMap<String, Box<dyn Geometry>>>> = OnceCell::new();

static RUN_DIRECTORY: OnceCell<PathBuf> = OnceCell::new();
static JVM: OnceCell<RwLock<JavaVM>> = OnceCell::new();
static YARN_CLASS_LOADER: OnceCell<GlobalRef> = OnceCell::new();

type Task = Box<dyn FnOnce() + Send + Sync>;

static TASK_CHANNELS: Lazy<(Sender<Task>, Receiver<Task>)> = Lazy::new(unbounded);
static MC_STATE: Lazy<ArcSwap<MinecraftRenderState>> = Lazy::new(|| {
    ArcSwap::new(Arc::new(MinecraftRenderState {
        _render_world: false,
    }))
});

static CLEAR_COLOR: Lazy<ArcSwap<[f32; 3]>> = Lazy::new(|| ArcSwap::new(Arc::new([0.0; 3])));

static AIR: Lazy<BlockstateKey> = Lazy::new(|| BlockstateKey {
    block: RENDERER
        .get()
        .unwrap()
        .mc
        .block_manager
        .read()
        .blocks
        .get_full("minecraft:air")
        .unwrap()
        .0 as u16,
    augment: 0,
});

static BLOCKS: Mutex<Vec<String>> = Mutex::new(Vec::new());
static BLOCK_STATES: Mutex<Vec<(String, String, GlobalRef)>> = Mutex::new(Vec::new());
pub static SETTINGS: RwLock<Option<Settings>> = RwLock::new(None);

pub static CLASSLOADER: OnceCell<WeakRef> = OnceCell::new();

/// Looks up a class through the loader the game was started with, and calls a static method on it.
///
/// `FindClass` on a thread that was attached from native code resolves against the *system* class
/// loader, which cannot see NeoForge's transformed game classes, so the loader has to be handed
/// over from the JVM side by [`setClassLoader`].
///
/// Every failure here is an `Err`, never a panic. This is reached from
/// [`MinecraftResourceManagerAdapter::get_bytes`], which wgpu-mc calls from whatever thread is
/// loading a resource, and a panic inside a `#[jni_fn]` cannot unwind - it aborts the whole
/// process, which is how a missing class loader used to take the game down.
pub fn call_static_from_class_loader<'env>(
    env: &mut JNIEnv<'env>,
    class: &str,
    method: &str,
    sig: &str,
    args: &[JValue],
) -> jni::errors::Result<JValueOwned<'env>> {
    let Some(class_loader) = CLASSLOADER.get() else {
        return Err(jni::errors::Error::NullPtr(
            "the game's class loader was never registered - see setClassLoader",
        ));
    };

    // Only a weak reference is held, so it is legitimate for the JVM to have collected it.
    let Some(class_loader) = class_loader.upgrade_local(&*env)? else {
        return Err(jni::errors::Error::NullPtr(
            "the game's class loader has been garbage collected",
        ));
    };

    let arg = env.new_string(class)?;
    let class_obj: JClass = env
        .call_method(
            class_loader,
            "findClass",
            "(Ljava/lang/String;)Ljava/lang/Class;",
            &[JValue::Object(&arg)],
        )?
        .l()?
        .into();

    env.call_static_method(class_obj, method, sig, args)
}

/// Registers the class loader Rust calls back through.
///
/// Called by the JVM side as part of loading the native library, before anything can ask for a
/// resource. A weak reference is enough and is what [`call_static_from_class_loader`] expects: the
/// loader is owned by the mod loader for the lifetime of the process, so it cannot go away while
/// the game is running, and holding it strongly here would keep it - and every class it loaded -
/// alive past shutdown.
#[jni_fn("dev.birb.wgpu.rust.WgpuNative")]
pub fn setClassLoader(mut env: JNIEnv, _class: JClass, class_loader: JObject) {
    match env.new_weak_ref(class_loader) {
        Ok(Some(weak)) => {
            if CLASSLOADER.set(weak).is_err() {
                log::warn!("wgpu-mc: the game's class loader was registered more than once");
            }
        }
        Ok(None) => log::error!("wgpu-mc: the game's class loader was null"),
        Err(err) => log::error!("wgpu-mc: could not register the game's class loader: {err}"),
    }
}

#[derive(Debug)]
pub struct SectionHolder {
    pub block_data: Option<(JavaPalette, PackedIntegerArray)>,
    pub light_data: Option<DeserializedLightData>,
}

#[derive(Debug)]
pub struct MinecraftBlockstateProvider {
    pub sections: [Option<SectionHolder>; 27],
    pub air: BlockstateKey,
}
impl BlockStateProvider for MinecraftBlockstateProvider {
    fn get_state(&self, pos: IVec3) -> ChunkBlockState {
        let section_pos: IVec3 = (pos >> 4) + 1;
        let section_option =
            &self.sections[(section_pos.x + section_pos.y * 3 + section_pos.z * 9) as usize];

        let section = match section_option {
            None => return ChunkBlockState::Air,
            Some(chunk) => chunk,
        };

        let (palette, storage) = match &section.block_data {
            Some(section) => section,
            None => return ChunkBlockState::Air,
        };

        let palette_key = storage.get(pos.x & 15, pos.y & 15, pos.z & 15);
        let block = palette.get(palette_key as usize).unwrap();

        if *block == self.air {
            ChunkBlockState::Air
        } else {
            ChunkBlockState::State(*block)
        }
    }

    fn get_light_level(&self, pos: IVec3) -> LightLevel {
        let section_pos: IVec3 = (pos >> 4) + 1;
        let chunk_option =
            &self.sections[(section_pos.x + section_pos.y * 3 + section_pos.z * 9) as usize];

        let chunk = match chunk_option {
            None => return LightLevel::from_sky_and_block(0, 0),
            Some(chunk) => chunk,
        };

        let light_data = match &chunk.light_data {
            None => return LightLevel::from_sky_and_block(0, 0),
            Some(light_data) => light_data,
        };

        let local_x = pos.x & 0b1111;
        let local_y = pos.y & 0b1111;
        let local_z = pos.z & 0b1111;

        let packed_coords = ((local_y << 8) | (local_z << 4) | (local_x)) as usize;

        let shift = (packed_coords & 1) << 2;

        let array_index = packed_coords >> 1;

        let sky_light = (light_data.sky_light[array_index] >> shift) & 0b1111;
        let block_light = (light_data.block_light[array_index] >> shift) & 0b1111;

        LightLevel::from_sky_and_block(sky_light, block_light)
    }

    fn is_section_empty(&self, rel_pos: IVec3) -> bool {
        if rel_pos.abs().cmpgt(ivec3(1, 1, 1)).any() {
            return true;
        }

        self.sections[(rel_pos + 1).dot(ivec3(1, 3, 9)) as usize].is_none()
    }

    fn get_block_color(&self, _pos: IVec3, _tint_index: i32) -> u32 {
        0xffffffff
    }
}

struct MinecraftResourceManagerAdapter {
    jvm: JavaVM,
}

impl ResourceProvider for MinecraftResourceManagerAdapter {
    /// Reads a resource through the game's own resource provider.
    ///
    /// Nothing in here may panic. wgpu-mc calls this from whichever thread is loading a resource,
    /// and a panic on a `#[jni_fn]` frame cannot unwind - it aborts the JVM, so a single missing
    /// or unreadable file would take the whole game down instead of just that texture. The trait
    /// already models "no such resource" as `None`, so every failure becomes one, with a log line.
    fn get_bytes(&self, id: &ResourcePath) -> Option<Vec<u8>> {
        let mut env = match self.jvm.attach_current_thread() {
            Ok(env) => env,
            Err(err) => {
                log::error!("wgpu-mc: could not attach to the JVM to read {}: {err}", id.0);
                return None;
            }
        };

        let path = match env.new_string(&id.0) {
            Ok(path) => path,
            Err(err) => {
                log::error!("wgpu-mc: could not pass {} to the JVM: {err}", id.0);
                return None;
            }
        };

        let bytes: JByteArray = match call_static_from_class_loader(
            &mut env,
            "dev.birb.wgpu.rust.WgpuResourceProvider",
            "getResource",
            "(Ljava/lang/String;)[B",
            &[JValue::Object(&path.into())],
        )
        .and_then(|value| value.l())
        {
            Ok(bytes) => bytes.into(),
            Err(err) => {
                log::error!("wgpu-mc: {} could not be read: {err}", id.0);
                return None;
            }
        };

        // The provider answers with an empty array for a resource it does not have.
        if bytes.is_null() {
            return None;
        }

        let elements: AutoElements<jbyte> =
            match unsafe { env.get_array_elements(&bytes, ReleaseMode::NoCopyBack) } {
                Ok(elements) => elements,
                Err(err) => {
                    log::error!("wgpu-mc: could not read the bytes of {}: {err}", id.0);
                    return None;
                }
            };

        let size = elements.len();
        if size == 0 {
            return None;
        }

        Some(Vec::from(unsafe {
            slice::from_raw_parts(elements.as_ptr() as *const u8, size)
        }))
    }
}

#[jni_fn("dev.birb.wgpu.rust.WgpuNative")]
pub fn getSettingsStructure(env: JNIEnv, _class: JClass) -> jstring {
    env.new_string(crate::settings::SETTINGS_INFO_JSON.clone())
        .unwrap()
        .into_raw()
}

#[jni_fn("dev.birb.wgpu.rust.WgpuNative")]
pub fn getSettings(env: JNIEnv, _class: JClass) -> jstring {
    let json = match SETTINGS.read().as_ref() {
        Some(settings) => serde_json::to_string(settings).unwrap_or_else(|_| "{}".to_string()),
        None => {
            // The options screen is reachable before client setup in principle, and an `unwrap`
            // here would run the panic hook, which exits the game.
            log::warn!("wgpu-mc: settings were read before the run directory was sent");
            "{}".to_string()
        }
    };

    env.new_string(json).unwrap().into_raw()
}

/// Applies the settings the options screen sent, and persists them.
///
/// The write to disk is not optional: `sendRunDirectory` loads the settings from
/// `config/wgpu-mc-renderer.json` at startup, so a setting that is only stored in memory is lost
/// on the next launch. That matters most for `backend`, which by design cannot take effect until
/// the game is restarted - forgetting it would make the switch look like it did nothing at all.
#[jni_fn("dev.birb.wgpu.rust.WgpuNative")]
pub fn sendSettings(mut env: JNIEnv, _class: JClass, settings: JString) -> bool {
    if SETTINGS.read().is_none() {
        // `getSettings` hands out `{}` in this state, and every field has a serde default, so
        // accepting that would quietly write the defaults over the player's config.
        log::error!("wgpu-mc: refusing to save settings before the run directory was sent");
        return false;
    }

    let json: String = env.get_string(&settings).unwrap().into();
    let Ok(settings) = serde_json::from_str::<Settings>(json.as_str()) else {
        log::error!("wgpu-mc: the options screen sent settings that could not be parsed");
        return false;
    };

    if !settings.write() {
        // The settings are still applied below, so the running game behaves as asked; only the
        // next launch will not see them.
        log::error!("wgpu-mc: the renderer settings could not be saved and will be lost on exit");
    }

    // The debug switches are read on the draw path, so they are copied out of the settings rather
    // than looked up per draw. This is what makes an option on the debug page take effect the
    // moment it is applied - the switch is on the next draw, not on the next launch.
    crate::debug::apply(&settings);

    *SETTINGS.write() = Some(settings);

    // `vsync` only picks the swapchain's present mode, so unlike `backend` it can be applied here
    // and now: this re-resolves the mode from the settings that were just stored and reconfigures
    // the surface when it differs. A no-op for every other setting, and for a value that did not
    // change.
    crate::device::reapply_present_mode();

    true
}

#[jni_fn("dev.birb.wgpu.rust.WgpuNative")]
pub fn sendRunDirectory(mut env: JNIEnv, _class: JClass, dir: JString) {
    let dir: String = env.get_string(&dir).unwrap().into();
    let path = PathBuf::from(dir);

    // Called twice on purpose: once from the mod constructor, before the renderer exists and the
    // backend setting still matters, and once from client setup, which is where this used to live.
    // `OnceCell::set` fails the second time, and unwrapping that failure used to be a panic - which
    // runs the panic hook, which exits the game.
    if RUN_DIRECTORY.set(path).is_err() {
        return;
    }

    let mut write = SETTINGS.write();
    let settings = Settings::load_or_default();
    // Before the renderer exists in most launches, so the debug switches are already resolved by
    // the time the first draw asks for them.
    crate::debug::apply(&settings);
    *write = Some(settings);
}

#[jni_fn("dev.birb.wgpu.rust.WgpuNative")]
pub fn getBackend(env: JNIEnv, _class: JClass) -> jstring {
    let renderer = RENDERER.get().unwrap();
    let backend = renderer.get_backend_description();

    env.new_string(backend).unwrap().into_raw()
}

/// The adapter's vendor, name, API and driver, one per line.
///
/// Unlike [`getBackend`] this one answers with an empty string when there is no renderer yet - the
/// F3 overlay can be opened before one exists, and the JVM side has its own wording to fall back
/// to. See `WmRenderer#get_adapter_description` for why the four fields are the four it asks for.
#[jni_fn("dev.birb.wgpu.rust.WgpuNative")]
pub fn getAdapterInfo(env: JNIEnv, _class: JClass) -> jstring {
    let description = RENDERER
        .get()
        .map(WmRenderer::get_adapter_description)
        .unwrap_or_default();

    env.new_string(description).unwrap().into_raw()
}

#[jni_fn("dev.birb.wgpu.rust.WgpuNative")]
pub fn registerBlockState(
    mut env: JNIEnv,
    _class: JClass,
    block_state: JObject,
    block_name: JString,
    state_key: JString,
) {
    let global_ref = env.new_global_ref(block_state).unwrap();

    let block_name: String = env.get_string(&block_name).unwrap().into();
    let state_key: String = env.get_string(&state_key).unwrap().into();

    BLOCK_STATES
        .lock()
        .push((block_name, state_key, global_ref));
}

struct MinecraftBlockStateProviderWrapper<'a> {
    internal: MinecraftBlockstateProvider,
    env: RefCell<JNIEnv<'a>>,
}

impl<'a> BlockStateProvider for MinecraftBlockStateProviderWrapper<'a> {
    fn get_state(&self, pos: IVec3) -> ChunkBlockState {
        self.internal.get_state(pos)
    }

    fn get_light_level(&self, pos: IVec3) -> LightLevel {
        self.internal.get_light_level(pos)
    }

    fn is_section_empty(&self, rel_pos: IVec3) -> bool {
        self.internal.is_section_empty(rel_pos)
    }

    fn get_block_color(&self, pos: IVec3, tint_index: i32) -> u32 {
        self.env
            .borrow_mut()
            .call_static_method(
                "dev/birb/wgpu/render/Wgpu",
                "helperGetBlockColor",
                "(IIII)I",
                &[
                    JValue::Int(pos.x),
                    JValue::Int(pos.y),
                    JValue::Int(pos.z),
                    JValue::Int(tint_index),
                ],
            )
            .unwrap()
            .i()
            .unwrap() as u32
    }
}

#[jni_fn("dev.birb.wgpu.rust.WgpuNative")]
pub fn bakeSection(
    mut env: JNIEnv,
    _class: JClass,
    x: jint,
    y: jint,
    z: jint,
    paletteIndices: JLongArray,
    storageIndices: JLongArray,
    blockBytes: JObjectArray,
    skyBytes: JObjectArray,
) {
    let palette_elements =
        unsafe { env.get_array_elements(&paletteIndices, ReleaseMode::NoCopyBack) }.unwrap();
    let palettes =
        unsafe { slice::from_raw_parts(palette_elements.as_ptr(), palette_elements.len()) };
    let storage_elements =
        unsafe { env.get_array_elements(&storageIndices, ReleaseMode::NoCopyBack) }.unwrap();
    let storages =
        unsafe { slice::from_raw_parts(storage_elements.as_ptr(), storage_elements.len()) };
    const NONE: Option<SectionHolder> = None;
    let mut bsp = MinecraftBlockstateProvider {
        sections: [NONE; 27],
        air: *AIR,
    };

    for i in 0..27 {
        let mut palette_storage = PALETTE_STORAGE.write();
        let mut pia_storage = PIA_STORAGE.write();

        let block_data = if palette_storage.contains(palettes[i] as usize)
            && pia_storage.contains(storages[i] as usize)
        {
            Some((
                palette_storage.remove(palettes[i] as usize),
                pia_storage.remove(storages[i] as usize),
            ))
        } else {
            None
        };
        let sky_array = unsafe {
            JPrimitiveArray::from_raw(
                env.get_object_array_element(&skyBytes, i as jsize)
                    .unwrap()
                    .into_raw(),
            )
        };
        let sky_bytes =
            unsafe { env.get_array_elements(&sky_array, ReleaseMode::NoCopyBack) }.unwrap();
        let block_array = unsafe {
            JPrimitiveArray::from_raw(
                env.get_object_array_element(&blockBytes, i as jsize)
                    .unwrap()
                    .into_raw(),
            )
        };
        let block_bytes =
            unsafe { env.get_array_elements(&block_array, ReleaseMode::NoCopyBack) }.unwrap();

        bsp.sections[i] = Some(SectionHolder {
            block_data,
            light_data: Some(DeserializedLightData {
                sky_light: Box::new(
                    unsafe { slice::from_raw_parts(sky_bytes.as_ptr(), sky_bytes.len()) }
                        .try_into()
                        .unwrap(),
                ),
                block_light: Box::new(
                    unsafe { slice::from_raw_parts(block_bytes.as_ptr(), block_bytes.len()) }
                        .try_into()
                        .unwrap(),
                ),
            }),
        });
    }

    // THREAD_POOL.get().unwrap().spawn(move || {
    let wm = RENDERER.get().unwrap();
    // let env = jvm.attach_current_thread_as_daemon().unwrap();

    let wrapper = MinecraftBlockStateProviderWrapper {
        internal: bsp,
        env: RefCell::new(env),
    };

    bake_section(ivec3(x, y, z), wm, &wrapper);
    // })
}

#[jni_fn("dev.birb.wgpu.rust.WgpuNative")]
pub fn registerBlock(mut env: JNIEnv, _class: JClass, name: JString) {
    let name: String = env.get_string(&name).unwrap().into();

    BLOCKS.lock().push(name);
}

#[jni_fn("dev.birb.wgpu.rust.WgpuNative")]
pub fn cacheBlockStates(mut env: JNIEnv, _class: JClass) {
    let wm = RENDERER.get().unwrap();
    {
        let blocks = BLOCKS.lock();

        let blockstates = blocks
            .iter()
            .map(|identifier| {
                (
                    identifier.clone(),
                    ResourcePath::from(&identifier[..])
                        .prepend("blockstates/")
                        .append(".json"),
                )
            })
            .collect::<Vec<_>>();

        wm.mc.bake_blocks(
            wm,
            blockstates
                .iter()
                .map(|(string, resource)| (string, resource)),
        );
    }

    let mut states = BLOCK_STATES.lock();

    let block_manager = wm.mc.block_manager.write();
    let mut mappings = Vec::new();

    let mut stdout = stdout().lock();

    states
        .iter()
        .for_each(|(block_name, state_key, global_ref)| {
            let (id_key, _, wm_block) = block_manager.blocks.get_full(block_name).unwrap();

            let key_iter = if !state_key.is_empty() {
                state_key
                    .split(',')
                    .filter_map(|kv_pair| {
                        let mut split = kv_pair.split('=');
                        if kv_pair.is_empty() {
                            return None;
                        }

                        Some((
                            split.next().unwrap(),
                            match split.next().unwrap() {
                                "true" => StateValue::Bool(true),
                                "false" => StateValue::Bool(false),
                                other => StateValue::String(other.into()),
                            },
                        ))
                    })
                    .collect::<Vec<_>>()
            } else {
                vec![]
            };
            let atlases = wm.mc.texture_manager.atlases.write();
            let atlas = &atlases[BLOCK_ATLAS];
            let model = wm_block.get_model_by_key(
                key_iter
                    .iter()
                    .filter(|(a, _)| *a != "waterlogged")
                    .map(|(a, b)| (*a, b)),
                &*wm.mc.resource_provider,
                atlas,
                0,
            );
            let fallback_key = block_manager.blocks.get_full("minecraft:bedrock").unwrap();

            let key = match model {
                Some((_, augment)) => BlockstateKey {
                    block: id_key as u16,
                    augment,
                },
                None => BlockstateKey {
                    block: fallback_key.0 as u16,
                    augment: 0,
                },
            };

            if key.block == fallback_key.0 as u16 {
                writeln!(&mut stdout, "{} {}", block_name, state_key).unwrap();
            }

            mappings.push((key, global_ref));
        });

    drop(stdout);

    mappings.iter().for_each(|(blockstate_key, global_ref)| {
        env.call_static_method(
            "dev/birb/wgpu/render/Wgpu",
            "helperSetBlockStateIndex",
            "(Ljava/lang/Object;I)V",
            &[
                JValue::Object(global_ref.as_obj()),
                JValue::Int(blockstate_key.pack() as i32),
            ],
        )
        .unwrap();
    });

    let instant = Instant::now();

    let state_count = states.len();

    states.clear();

    let debug_message = format!(
        "Released {} global refs to BlockState objects in {}ms",
        state_count,
        Instant::now().duration_since(instant).as_millis()
    );

    let debug_jstring = env.new_string(debug_message).unwrap();

    env.call_static_method(
        "dev/birb/wgpu/render/Wgpu",
        "rustDebug",
        "(Ljava/lang/String;)V",
        &[JValue::Object(&unsafe {
            JObject::from_raw(debug_jstring.into_raw())
        })],
    )
    .unwrap();
}

#[allow(unused_must_use)]
#[jni_fn("dev.birb.wgpu.rust.WgpuNative")]
pub fn setPanicHook(env: JNIEnv, _class: JClass) {
    // `env_logger::init` alone drops everything below `error`, which hides the renderer's own
    // reporting (swapchain configuration, adapter choice, the frame dump). The default filter keeps
    // this crate and `wgpu-mc` at `info` while leaving `wgpu` and `naga` at `warn`, so what is
    // printed is the mod's own, and `RUST_LOG` still overrides it.
    env_logger::Builder::from_env(
        env_logger::Env::default()
            .default_filter_or("wgpu_mc_jni=info,wgpu_mc=info,wgpu=warn,naga=warn"),
    )
    .init();

    let jvm = env.get_java_vm().unwrap();
    let jvm_ptr = jvm.get_java_vm_pointer() as usize;

    std::panic::set_hook(Box::new(move |panic_info| {
        println!("{panic_info}");

        // A panic that unwinds through the C ABI ends the process, and the process ending takes
        // whatever stderr still had buffered with it - a crash report with no message in the log is
        // exactly what the last screenshot crash looked like. So it also goes to a file, which is
        // flushed as it is written.
        if let Some(run_directory) = RUN_DIRECTORY.get() {
            let path = run_directory.join("wgpu-panic.txt");
            if let Ok(mut file) = std::fs::OpenOptions::new().create(true).append(true).open(&path) {
                use std::io::Write;
                let _ = writeln!(file, "{panic_info}");
            }
        }

        let jvm = unsafe { JavaVM::from_raw(jvm_ptr as _).unwrap() };
        let mut env = jvm.attach_current_thread_permanently().unwrap();

        let message = format!("wgpu-mc has panicked. Minecraft will now exit.\n{panic_info}");
        let jstring = env.new_string(message).unwrap();

        //Does not return
        env.call_static_method(
            "dev/birb/wgpu/render/Wgpu",
            "rustPanic",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&JObject::from(jstring))],
        );
    }))
}

#[jni_fn("dev.birb.wgpu.rust.WgpuNative")]
pub fn setWorldRenderState(_env: JNIEnv, _class: JClass, boolean: jboolean) {
    MC_STATE.store(Arc::new(MinecraftRenderState {
        _render_world: boolean != 0,
    }));
}

