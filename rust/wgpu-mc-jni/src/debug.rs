//! The debug switches, and the marker files they replace.
//!
//! Every diagnostic this renderer has grew as a file in the run directory - `wgpu-dump-frames`,
//! `wgpu-no-bind-group-cache`, `wgpu-trace-dynamic-offsets`, `wgpu-dump-shaders` - because a file
//! needs no launcher support and no rebuild. They are options on the options screen now, and the
//! markers keep working: a flag is on when the setting is on *or* the marker exists, so a run that
//! was started with a marker behaves as it did. The two that are the wrong way round (a marker that
//! turns something *off*) are handled where they are resolved.
//!
//! The flags live in atomics rather than being read from the settings on use. They are read on the
//! draw path - once per draw for `diagnostics`, once per pipeline bind for the rest - and the
//! settings are behind a lock. [`apply`] is what moves a setting into the atomics: it runs whenever
//! the settings are loaded or sent, which is startup and every Apply on the options screen.

use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;

use crate::settings::{DebugSettings, Settings};

/// Whether to report what the renderer is doing: pipeline binds, passes, counters, shader dumps.
static DIAGNOSTICS: AtomicBool = AtomicBool::new(false);

/// Whether a draw may reuse a bind group built for another draw at a different dynamic offset.
static BIND_GROUP_CACHE: AtomicBool = AtomicBool::new(true);

/// Whether uniform bindings carry their offset dynamically instead of baking it into the set.
static DYNAMIC_OFFSETS: AtomicBool = AtomicBool::new(true);

/// Whether every draw's bindings are logged, which is a line per draw.
static TRACE_DYNAMIC_OFFSETS: AtomicBool = AtomicBool::new(false);

/// Whether the GLSL that reaches the shader compiler is written out.
static DUMP_SHADERS: AtomicBool = AtomicBool::new(false);

/// Whether frame timings are measured with GPU timestamp queries.
static GPU_TIMESTAMPS: AtomicBool = AtomicBool::new(false);

/// Whether a PIX timing capture has been asked for.
static PIX_CAPTURE: AtomicBool = AtomicBool::new(false);

/// Whether the wgpu instance is created with the driver's GPU-based validation.
///
/// Read once, when the instance is built: an instance flag cannot be changed afterwards, which is
/// why the setting that feeds it is marked as needing a restart.
static GPU_BASED_VALIDATION: AtomicBool = AtomicBool::new(false);

#[inline]
pub fn diagnostics() -> bool {
    DIAGNOSTICS.load(Ordering::Relaxed)
}

#[inline]
pub fn bind_group_cache() -> bool {
    BIND_GROUP_CACHE.load(Ordering::Relaxed)
}

#[inline]
pub fn dynamic_offsets() -> bool {
    DYNAMIC_OFFSETS.load(Ordering::Relaxed)
}

#[inline]
pub fn trace_dynamic_offsets() -> bool {
    TRACE_DYNAMIC_OFFSETS.load(Ordering::Relaxed)
}

#[inline]
pub fn dump_shaders() -> bool {
    DUMP_SHADERS.load(Ordering::Relaxed)
}

#[inline]
pub fn gpu_timestamps() -> bool {
    GPU_TIMESTAMPS.load(Ordering::Relaxed)
}

#[inline]
pub fn pix_capture() -> bool {
    PIX_CAPTURE.load(Ordering::Relaxed)
}

#[inline]
pub fn gpu_based_validation() -> bool {
    GPU_BASED_VALIDATION.load(Ordering::Relaxed)
}

/// Resolves every flag from the settings and the marker files.
pub fn apply(settings: &Settings) {
    let DebugSettings {
        gpu_based_validation,
        diagnostics,
        bind_group_cache,
        dynamic_offsets,
        trace_dynamic_offsets,
        dump_shaders,
        gpu_timestamps,
        pix_capture,
    } = settings.debug();

    set(&DIAGNOSTICS, diagnostics || marker("wgpu-dump-frames"));
    // These two markers are spelled as the *off* switch, so the file wins over the setting.
    set(
        &BIND_GROUP_CACHE,
        bind_group_cache && !marker("wgpu-no-bind-group-cache"),
    );
    set(
        &DYNAMIC_OFFSETS,
        dynamic_offsets && !marker("wgpu-no-dynamic-offsets"),
    );
    set(
        &TRACE_DYNAMIC_OFFSETS,
        trace_dynamic_offsets || marker("wgpu-trace-dynamic-offsets"),
    );
    set(&DUMP_SHADERS, dump_shaders || marker("wgpu-dump-shaders"));
    set(&GPU_BASED_VALIDATION, gpu_based_validation);

    // These two are not flags to be read somewhere: they *are* the action, so the switch does
    // something the moment it moves. Both are gated on the setting alone - a marker file has no way
    // to end a capture, and a capture that never ends is worse than none.
    set(&GPU_TIMESTAMPS, gpu_timestamps);
    set(&PIX_CAPTURE, pix_capture);

    crate::timing::set_enabled(gpu_timestamps);
    crate::pix::set_capturing(pix_capture);
}

fn set(flag: &AtomicBool, value: bool) {
    if flag.swap(value, Ordering::Relaxed) != value {
        log::info!(
            "wgpu-mc: {} is now {}",
            name(flag),
            if value { "on" } else { "off" }
        );
    }
}

/// A flag's name for the log, which is the one its setting has.
fn name(flag: &AtomicBool) -> &'static str {
    match flag {
        f if std::ptr::eq(f, &DIAGNOSTICS) => "diagnostics",
        f if std::ptr::eq(f, &BIND_GROUP_CACHE) => "bind group cache",
        f if std::ptr::eq(f, &DYNAMIC_OFFSETS) => "dynamic offsets",
        f if std::ptr::eq(f, &TRACE_DYNAMIC_OFFSETS) => "trace dynamic offsets",
        f if std::ptr::eq(f, &DUMP_SHADERS) => "dump shaders",
        f if std::ptr::eq(f, &GPU_TIMESTAMPS) => "gpu timestamps",
        f if std::ptr::eq(f, &PIX_CAPTURE) => "pix capture",
        _ => "gpu based validation",
    }
}

/// Whether a marker file exists, asked once per process.
///
/// A marker is a file dropped next to the mod, and it used to be read on every draw for the cache
/// and the offset switches. It cannot appear or disappear meaningfully while the game is running -
/// the settings are the live switch now - so it is resolved the first time it is asked for, which
/// is when the settings are applied.
fn marker(file_name: &'static str) -> bool {
    static MARKERS: OnceLock<std::collections::HashSet<&'static str>> = OnceLock::new();

    MARKERS
        .get_or_init(|| {
            const NAMES: [&str; 5] = [
                "wgpu-dump-frames",
                "wgpu-no-bind-group-cache",
                "wgpu-no-dynamic-offsets",
                "wgpu-trace-dynamic-offsets",
                "wgpu-dump-shaders",
            ];

            let mut present = std::collections::HashSet::new();

            for name in NAMES {
                if Path::new(name).exists() || run_directory_marker(name) {
                    present.insert(name);
                }
            }

            present
        })
        .contains(file_name)
}

/// The same marker, next to the game directory rather than in the process's working directory.
///
/// The two are the same directory in a normal launch, and the run directory is the one that is
/// still right when the game is started from somewhere else. It is only known once the JVM has
/// sent it, so both are checked.
fn run_directory_marker(file_name: &str) -> bool {
    crate::RUN_DIRECTORY
        .get()
        .map(|directory| directory.join(file_name).exists())
        .unwrap_or(false)
}
