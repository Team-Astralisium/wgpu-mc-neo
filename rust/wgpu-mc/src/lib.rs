/*!
# wgpu-mc
wgpu-mc is a pure-Rust crate which is designed to be usable by anyone who needs to render
Minecraft-style scenes using Rust. The main user of this crate at this time is the Minecraft mod
Electrum which replaces Minecraft's official renderer with wgpu-mc.
However, anyone is able to use this crate, and the API is designed to be completely independent
of any single project, allowing anyone to use it. It is mostly batteries-included, except for a
few things.

# Considerations

This crate is unstable and subject to change. The basic structure for features such
as terrain rendering and entity rendering are already in-place but could very well change significantly
in the future.

# Setup

wgpu-mc, as you could have probably guessed, uses the [wgpu](https://github.com/gfx-rs/wgpu) crate
for communicating with the GPU. Assuming you aren't running wgpu-mc headless (if you are, I assume
you already know what you're doing), wgpu-mc can handle surface and device setup for you, as long
as you pass in a valid window handle. See [init_wgpu]

# Rendering

wgpu-mc makes use of a trait called `WmPipeline` to describe any struct which is used for
rendering. There are multiple built in pipelines, but they aren't required to use while rendering.

## Terrain Rendering

The first step to begin terrain rendering is to implement [BlockStateProvider](cr).
This is a trait that provides a block state key for a given coordinate.

## Entity Rendering

To render entities, you need an entity model. wgpu-mc makes no assumptions about how entity models are defined,
so it's up to you to provide them to wgpu-mc.

See the [render::entity] module for an example of rendering an example entity.
 */

use std::borrow::Borrow;
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::mpsc::{Receiver, Sender, channel};

use glam::IVec3;
use mc::Scene;
use mc::chunk::BakedLayer;
pub use minecraft_assets;
use parking_lot::{Mutex, RwLock};
pub use wgpu;
use wgpu::{BindGroupDescriptor, BindGroupEntry, BindGroupLayout, BufferDescriptor, Surface};
use winit::dpi::PhysicalSize;
use winit::window::Window;

use crate::mc::MinecraftState;
use crate::mc::resource::ResourceProvider;
use crate::render::atlas::Atlas;
use crate::render::pipeline::{BLOCK_ATLAS, ENTITY_ATLAS, create_bind_group_layouts};

pub mod mc;
pub mod render;
pub mod texture;
pub mod util;

pub use treeculler::Frustum;

/// Provides access to wgpu
pub struct Gpu {
    pub instance: wgpu::Instance,
    pub adapter: wgpu::Adapter,
    pub surface: Mutex<Option<Arc<Surface<'static>>>>,
    pub device: wgpu::Device,
    pub queue: wgpu::Queue,
    /// Driver-side pipeline compilation results, carried across runs where the backend supports it.
    ///
    /// Owned by the device it was created for: a cache from another device - or from the same one
    /// after a driver update - is rejected entry by entry, so it is kept beside the device rather
    /// than in a process-wide cell that a backend fallback would leave pointing at a dead device.
    /// `None` on the backends that do not implement one, which is DX12.
    pub pipeline_cache: Option<wgpu::PipelineCache>,
}

/// Tuple of chunk positions and baked layers
pub type ChunkUpdateData = (IVec3, Vec<BakedLayer>);

/// The main wgpu-mc renderer struct
/// Resources pertaining to Minecraft go in `MinecraftState`.
///
/// `RenderGraph` is used in tandem with `World` to render scenes.
pub struct WmRenderer {
    pub gpu: Arc<Gpu>,
    pub bind_group_layouts: Arc<HashMap<String, BindGroupLayout>>,
    pub mc: MinecraftState,
    pub chunk_update_queue: (Sender<ChunkUpdateData>, Mutex<Receiver<ChunkUpdateData>>),
}

#[derive(Copy, Clone)]
pub struct WindowSize {
    pub width: u32,
    pub height: u32,
}

pub trait HasWindowSize {
    fn get_window_size(&self) -> WindowSize;
}

impl WmRenderer {
    pub fn new(display: Arc<Gpu>, resource_provider: Arc<dyn ResourceProvider>) -> WmRenderer {
        let mc = MinecraftState::new(&display, resource_provider);
        let (sender, receiver) = channel();
        Self {
            bind_group_layouts: Arc::new(create_bind_group_layouts(&display.device)),
            gpu: display,
            mc,
            chunk_update_queue: (sender, Mutex::new(receiver)),
        }
    }

    pub fn init(&self) {
        let atlases = [BLOCK_ATLAS, ENTITY_ATLAS]
            .iter()
            .map(|&name| (name.into(), Atlas::new(&self.gpu, false)))
            .collect();

        *self.mc.texture_manager.atlases.write() = atlases;
    }

    pub fn upload_animated_block_buffer(&self, data: Vec<f32>) {
        let d = data.as_slice();

        let buf = self.mc.animated_block_buffer.borrow().load_full();

        if buf.is_none() {
            let animated_block_buffer = self.gpu.device.create_buffer(&BufferDescriptor {
                label: None,
                size: (d.len() * 8) as wgpu::BufferAddress,
                usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: false,
            });
            let animated_block_bind_group =
                self.gpu.device.create_bind_group(&BindGroupDescriptor {
                    label: None,
                    layout: self.bind_group_layouts.get("ssbo").unwrap(),
                    entries: &[BindGroupEntry {
                        binding: 0,
                        resource: wgpu::BindingResource::Buffer(
                            animated_block_buffer.as_entire_buffer_binding(),
                        ),
                    }],
                });

            self.mc
                .animated_block_buffer
                .store(Arc::new(Some(animated_block_buffer)));
            self.mc
                .animated_block_bind_group
                .store(Arc::new(Some(animated_block_bind_group)));
        }

        self.gpu.queue.write_buffer(
            (**self.mc.animated_block_buffer.load()).as_ref().unwrap(),
            0,
            bytemuck::cast_slice(d),
        );
    }

    pub fn submit_chunk_updates(&self, scene: &Scene) {
        let receiver = self.chunk_update_queue.1.lock();
        let updates = receiver.try_iter();

        updates.for_each(|(pos, layers)| {
            let mut storage = scene.section_storage.write();
            let section = storage.replace(pos, &layers);
            for (i, ranges) in section.layers.iter().enumerate() {
                if let Some(ranges) = ranges {
                    self.gpu.queue.write_buffer(
                        &scene.chunk_buffer.buffer,
                        ranges.vertex_range.start as u64 * 4,
                        &layers[i].vertices,
                    );
                    self.gpu.queue.write_buffer(
                        &scene.chunk_buffer.buffer,
                        ranges.index_range.start as u64 * 4,
                        &layers[i].indices,
                    );
                }
            }
        });
    }

    pub fn get_backend_description(&self) -> String {
        format!(
            "wgpu {} ({})",
            env!("WGPUMC_WGPU_VER"),
            self.gpu.adapter.get_info().backend.to_str()
        )
    }

    /// How the adapter behind this renderer introduces itself, one field per line.
    ///
    /// The F3 overlay's vanilla system block asks the device for a vendor, a renderer name, a
    /// backend name and a version. On the OpenGL backend those four answers are `GL_VENDOR`,
    /// `GL_RENDERER`, "OpenGL" and `GL_VERSION` - the graphics driver, introducing itself - and
    /// wgpu carries the same information in `AdapterInfo`. Handing it over lets that block keep
    /// looking the way it does on GL instead of reading "wgpu / wgpu-mc / vulkan / wgpu 29".
    ///
    /// Four lines in this order, none of them empty:
    ///
    /// 1. the vendor (`NVIDIA`, `AMD`, ...), named from the PCI id
    /// 2. the adapter's own name (`NVIDIA GeForce RTX 4060 Laptop GPU`)
    /// 3. the API it is driven through (`Vulkan`, `DirectX 12`, ...)
    /// 4. the driver and its version
    ///
    /// The fourth line joins `driver` and `driver_info` rather than choosing between them, because
    /// the backends fill them in differently: Vulkan reports the driver's name and version
    /// ("NVIDIA" and "552.44"), while DX12 puts the version in `driver` and leaves `driver_info`
    /// empty ("31.0.101.5333"). Either way the result names the installed graphics driver.
    pub fn get_adapter_description(&self) -> String {
        let info = self.gpu.adapter.get_info();

        let vendor = match vendor_name(info.vendor) {
            "" => format!("vendor 0x{:04x}", info.vendor),
            name => name.to_string(),
        };
        let driver = [info.driver.trim(), info.driver_info.trim()]
            .into_iter()
            .filter(|part| !part.is_empty())
            .collect::<Vec<_>>()
            .join(" ");

        [
            vendor,
            info.name,
            backend_name(info.backend).to_string(),
            if driver.is_empty() {
                "unknown driver".to_string()
            } else {
                driver
            },
        ]
        .join("\n")
    }
}

/// PCI vendor ids, as the names the drivers use for themselves.
///
/// `AdapterInfo#vendor` is the raw id the driver reports, so this is the only place it becomes
/// something a person can read; an id that is not listed stays empty and the caller falls back to
/// printing the number.
fn vendor_name(vendor: u32) -> &'static str {
    match vendor {
        0x10DE => "NVIDIA",
        0x1002 | 0x1022 => "AMD",
        0x8086 | 0x8087 => "Intel",
        0x106B => "Apple",
        0x13B5 => "ARM",
        0x5143 => "Qualcomm",
        0x1010 => "Imagination Technologies",
        0x1AE0 => "Google",
        _ => "",
    }
}

/// The API's own name, rather than the identifier wgpu uses for it.
///
/// Matched exhaustively on purpose: a backend added to wgpu should show up as a compile error here
/// rather than as a blank line in the overlay.
fn backend_name(backend: wgpu::Backend) -> &'static str {
    match backend {
        wgpu::Backend::Vulkan => "Vulkan",
        wgpu::Backend::Dx12 => "DirectX 12",
        wgpu::Backend::Metal => "Metal",
        wgpu::Backend::Gl => "OpenGL",
        wgpu::Backend::BrowserWebGpu => "WebGPU",
        wgpu::Backend::Noop => "no backend",
    }
}
