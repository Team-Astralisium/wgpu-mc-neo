//! Programmatic PIX captures, through the WinPixEventRuntime.
//!
//! PIX is Microsoft's D3D12 debugger. A *timing capture* records what the title did - GPU work,
//! PIX GPU events, API markers, and optionally CPU samples and callstacks - and PIX reads it back
//! on the machine that took it. It is normally started from the PIX UI; a title can also ask for
//! one itself, which is what this is for: `PIXBeginCapture(PIX_CAPTURE_TIMING, params)` starts it
//! and `PIXEndCapture` finishes it, with the parameters naming the file and saying what to record.
//!
//! The runtime is not part of Windows: `WinPixEventRuntime.dll` ships with PIX (or with the
//! WinPixEventRuntime NuGet package), and a game that does not have it next to itself or on `PATH`
//! simply cannot take a capture. That is why the DLL is loaded on demand and every failure is
//! reported with what was missing - the alternative, linking against it, would make the mod fail to
//! load on every machine without PIX installed.
//!
//! Everything here is Windows-only. The capture is a no-op elsewhere, which the switch says.

use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU8, Ordering};

/// The capture type flags of `pix3.h`. They are flags, not an enumeration: a `DWORD` with one bit
/// set, and `PIX_CAPTURE_TIMING` is the system timing capture this asks for.
const PIX_CAPTURE_TIMING: u32 = 1 << 0;

/// `PIXCaptureStorage::Memory`: fill the tooling memory, then stop. The other mode is a ring buffer
/// for captures that run for a long time, which a switch-driven capture does not need.
const PIX_CAPTURE_STORAGE_MEMORY: u32 = 0;

/// `S_FALSE`, which is what `PIXBeginCapture` returns on success - not `S_OK`.
const S_FALSE: i32 = 1;

/// How many frames a capture records before it stops itself.
///
/// A programmatic capture fills the tooling memory it is given and then discards what comes after,
/// so leaving one running for a session records the wrong thing and holds gigabytes while it does
/// it. Ten seconds of a world is what a timing capture is for; the switch can be turned on again
/// for another one, which writes the next file.
const CAPTURE_FRAMES: u32 = 600;

/// What the `pix capture` switch asks for, and what is actually happening.
///
/// Two states rather than one because the switch is applied during mod construction - before the
/// logger exists and before there is a frame to capture - and because a capture ends by itself: the
/// request is recorded there and acted on at a frame boundary, which is also where the answer can
/// be logged.
static WANTED: AtomicBool = AtomicBool::new(false);

/// `Idle`, `Running` or `Finished`: see [`tick`].
static STATE: AtomicU8 = AtomicU8::new(IDLE);

/// The frames the running capture has seen.
static FRAMES: AtomicU32 = AtomicU32::new(0);

const IDLE: u8 = 0;
const RUNNING: u8 = 1;
/// A capture ran to its frame limit. It stays here until the switch is turned off, so that a
/// finished capture does not immediately start another one.
const FINISHED: u8 = 2;

/// The `PIXCaptureParameters` union from `pix3.h`, in its timing-capture shape.
///
/// Written out by hand because the header is not part of any SDK this crate builds against. The
/// layout is the one Microsoft documents for `TimingCaptureParameters`: the file name, the tooling
/// memory budget, the storage mode, then the `BOOL` switches - and because the GPU capture member
/// of the union is a single pointer, the union's size is this struct's size, so passing a pointer
/// to it is what `PIXBeginCapture` expects either way.
#[repr(C)]
struct PixCaptureParameters {
    file_name: *const u16,
    maximum_tooling_memory_size_mb: u32,
    capture_storage: u32,
    capture_gpu_timing: i32,
    capture_callstacks: i32,
    capture_cpu_samples: i32,
    cpu_samples_per_second: u32,
    capture_file_io: i32,
    capture_virtual_alloc_events: i32,
    capture_heap_alloc_events: i32,
    capture_xmem_events: i32,
    capture_pix_mem_events: i32,
    capture_page_fault_events: i32,
    capture_video_frames: i32,
}

type BeginCapture = unsafe extern "system" fn(u32, *const PixCaptureParameters) -> i32;
type EndCapture = unsafe extern "system" fn(i32) -> i32;

struct PixRuntime {
    begin: BeginCapture,
    end: EndCapture,
}

/// The loaded runtime, or `None` if this machine has no usable PIX.
///
/// Loaded once: the DLLs either exist or they do not, and a capture that failed for a missing DLL
/// will fail again.
fn runtime() -> Option<&'static PixRuntime> {
    static RUNTIME: once_cell::sync::OnceCell<Option<PixRuntime>> = once_cell::sync::OnceCell::new();

    RUNTIME
        .get_or_init(|| {
            #[cfg(windows)]
            {
                // Safety: loading DLLs and taking function pointers out of them. Every pointer is
                // resolved by name and checked for null before it is called, and no library is ever
                // unloaded - the runtime has to stay mapped for the life of the process, since
                // `PIXEndCapture` can be called long after the load.
                unsafe { load_windows_runtime() }
            }

            #[cfg(not(windows))]
            {
                None
            }
        })
        .as_ref()
}

#[cfg(windows)]
unsafe fn load_windows_runtime() -> Option<PixRuntime> {
    use winapi::um::libloaderapi::{GetProcAddress, LoadLibraryW};

    // A programmatic *timing* capture needs both halves, and PIX ships only one of them:
    //
    //  * `WinPixTimingCapturer.dll` comes with PIX and has to be loaded into the process, which is
    //    what `PIXLoadLatestWinPixTimingCapturerLibrary()` in `pix3.h` does - that helper is a
    //    header function, so the search is written out in `pix_dll` here;
    //  * `WinPixEventRuntime.dll` is where `PIXBeginCapture` itself lives, and it comes from the
    //    WinPixEventRuntime package rather than from PIX, so it has to be beside the game (or on
    //    PATH). PIX's own install ships the OneCore build of the same runtime instead, which
    //    exports `PIXBeginCapture2` - the documented equivalent of `PIXBeginCapture`.
    let capturer = load_timing_capturer();

    let Some((module, name)) = load_event_runtime() else {
        log::error!(
            "wgpu-mc: PIX capture was asked for, but no WinPixEventRuntime.dll could be loaded. It \
             is not part of the PIX installer: put the one from the WinPixEventRuntime package next \
             to the game (or on PATH), and copy PIX's WinPixTimingCapturer.dll beside it"
        );
        return None;
    };

    if capturer.is_none() {
        log::error!(
            "wgpu-mc: PIX capture was asked for, and {name} is loaded, but no \
             WinPixTimingCapturer.dll was found next to the game or in {}\\<version> - a timing \
             capture needs PIX's capturer loaded into the process",
            PIX_INSTALL_ROOT
        );
    }

    // Safety: the module was just loaded, and both names are the ones `pix3.h` exports. The `2`
    // form is what PIX's own runtime build carries; the documentation calls the two equivalent.
    let begin = unsafe { GetProcAddress(module, c"PIXBeginCapture".as_ptr()) };
    let begin = if begin.is_null() {
        unsafe { GetProcAddress(module, c"PIXBeginCapture2".as_ptr()) }
    } else {
        begin
    };
    let end = unsafe { GetProcAddress(module, c"PIXEndCapture".as_ptr()) };

    if begin.is_null() || end.is_null() {
        log::error!("wgpu-mc: {name} has no PIXBeginCapture/PIXBeginCapture2 and PIXEndCapture");
        return None;
    }

    Some(PixRuntime {
        // Safety: the two symbols are exported with these signatures by every WinPixEventRuntime.
        begin: unsafe { std::mem::transmute::<_, BeginCapture>(begin) },
        end: unsafe { std::mem::transmute::<_, EndCapture>(end) },
    })
}

/// Where PIX installs itself, and the layout of that directory: one subdirectory per version.
#[cfg(windows)]
const PIX_INSTALL_ROOT: &str = r"C:\Program Files\Microsoft PIX";

/// Loads the event runtime, preferring the one beside the game over the one PIX ships.
#[cfg(windows)]
unsafe fn load_event_runtime() -> Option<(winapi::shared::minwindef::HMODULE, &'static str)> {
    use winapi::um::libloaderapi::LoadLibraryW;

    for name in ["WinPixEventRuntime.dll", "WinPixEventRuntime_OneCore.dll"] {
        let module = load_library(name, false);
        if !module.is_null() {
            log::info!("wgpu-mc: PIX: using {name}");
            return Some((module, name));
        }
    }

    // The OneCore build is in the PIX installation rather than anywhere the loader looks by
    // default, so it is tried by path.
    let path = pix_dll("WinPixEventRuntime_OneCore.dll")?;
    let module = load_library(&path.to_string_lossy(), true);
    if module.is_null() {
        return None;
    }

    log::info!("wgpu-mc: PIX: using {}", path.display());
    Some((module, "WinPixEventRuntime_OneCore.dll"))
}

/// Loads PIX's timing capturer, which the runtime needs in-process to take a timing capture.
///
/// Returned as a raw module handle that is deliberately never freed: the capturer has to stay
/// loaded for as long as a capture might start.
#[cfg(windows)]
unsafe fn load_timing_capturer() -> Option<winapi::shared::minwindef::HMODULE> {
    if let Some(path) = pix_dll("WinPixTimingCapturer.dll")
        && let module = load_library(&path.to_string_lossy(), true)
        && !module.is_null()
    {
        log::info!("wgpu-mc: PIX: timing capturer {}", path.display());
        return Some(module);
    }

    let module = load_library("WinPixTimingCapturer.dll", false);
    if module.is_null() {
        return None;
    }

    log::info!("wgpu-mc: PIX: timing capturer beside the game");
    Some(module)
}

/// The newest PIX installation's copy of a DLL, if PIX is installed.
///
/// PIX lays itself out as `<root>\<version>\`, so the versions are compared as numbers rather than
/// as strings - `2603.25` is newer than `2506.3`, and only the second component is ever small.
#[cfg(windows)]
fn pix_dll(file_name: &str) -> Option<std::path::PathBuf> {
    let mut versions: Vec<(u32, u32, std::path::PathBuf)> = std::fs::read_dir(PIX_INSTALL_ROOT)
        .ok()?
        .filter_map(|entry| {
            let path = entry.ok()?.path();
            if !path.is_dir() {
                return None;
            }

            let mut parts = path.file_name()?.to_str()?.split('.');
            let major = parts.next()?.parse().ok()?;
            let minor = parts.next().unwrap_or("0").parse().unwrap_or(0);
            let dll = path.join(file_name);

            dll.is_file().then_some((major, minor, dll))
        })
        .collect();

    versions.sort();
    versions.pop().map(|(_, _, dll)| dll)
}

/// `LoadLibraryW`, with the search order a side-by-side DLL needs.
#[cfg(windows)]
unsafe fn load_library(name: &str, by_path: bool) -> winapi::shared::minwindef::HMODULE {
    use winapi::um::libloaderapi::{LOAD_WITH_ALTERED_SEARCH_PATH, LoadLibraryExW};

    let wide: Vec<u16> = name.encode_utf16().chain(std::iter::once(0)).collect();

    // `LOAD_WITH_ALTERED_SEARCH_PATH` when the path is known makes the DLL's own directory the
    // first place its dependencies are looked for, which is what PIX's capturer needs to find the
    // rest of the installation.
    //
    // Safety: `wide` is NUL-terminated and lives for the call.
    unsafe {
        LoadLibraryExW(
            wide.as_ptr(),
            std::ptr::null_mut(),
            if by_path { LOAD_WITH_ALTERED_SEARCH_PATH } else { 0 },
        )
    }
}

/// Starts or stops a PIX timing capture, following the `pix capture` debug switch.
///
/// Called when the settings are applied, which is on the render thread - the thread whose work the
/// capture is about.
pub fn set_capturing(enabled: bool) {
    WANTED.store(enabled, Ordering::Relaxed);
}

/// Follows the switch: starts the capture it asks for, stops the one it no longer wants, and stops
/// a capture that has recorded its [`CAPTURE_FRAMES`].
///
/// Called once per presented frame, so a capture starts and ends at a frame boundary - and so that
/// what PIX says about it is logged when there is a log to write to.
pub fn tick() {
    let wanted = WANTED.load(Ordering::Relaxed);
    let state = STATE.load(Ordering::Relaxed);

    match (wanted, state) {
        // The switch is on and nothing is running: take the capture it asks for.
        (true, IDLE) => {
            FRAMES.store(1, Ordering::Relaxed);
            STATE.store(if begin() { RUNNING } else { FINISHED }, Ordering::Relaxed);
        }
        // Still running: count frames, and stop at the limit rather than filling tooling memory.
        (true, RUNNING) => {
            if FRAMES.fetch_add(1, Ordering::Relaxed) + 1 >= CAPTURE_FRAMES {
                end();
                STATE.store(FINISHED, Ordering::Relaxed);
                log::info!("wgpu-mc: PIX timing capture reached its {CAPTURE_FRAMES} frames and stopped");
            }
        }
        // The switch went off while a capture was running: stop it.
        (false, RUNNING) => {
            end();
            STATE.store(IDLE, Ordering::Relaxed);
        }
        // A finished capture is re-armed once the switch goes off, so turning it back on starts the
        // next one.
        (false, FINISHED) => STATE.store(IDLE, Ordering::Relaxed),
        _ => {}
    }
}

/// Starts a capture, and answers whether one is running.
fn begin() -> bool {
    let Some(file) = capture_file() else {
        log::error!("wgpu-mc: no run directory to write a PIX capture to");
        return false;
    };

    let result = call_on_its_own_thread({
        let file = file.clone();

        move || {
            let Some(runtime) = runtime() else {
                return None;
            };

            // The parameters take a wide string, so the UTF-16 buffer has to outlive the call.
            let mut name: Vec<u16> = file.to_string_lossy().encode_utf16().collect();
            name.push(0);

            let parameters = PixCaptureParameters {
                file_name: name.as_ptr(),
                // Both of these are ignored by PIX on Windows, and are here because the struct is
                // the same one the console API takes.
                maximum_tooling_memory_size_mb: 4096,
                capture_storage: PIX_CAPTURE_STORAGE_MEMORY,
                capture_gpu_timing: 1,
                capture_callstacks: 0,
                capture_cpu_samples: 0,
                cpu_samples_per_second: 0,
                capture_file_io: 0,
                capture_virtual_alloc_events: 0,
                capture_heap_alloc_events: 0,
                capture_xmem_events: 0,
                capture_pix_mem_events: 0,
                capture_page_fault_events: 0,
                capture_video_frames: 0,
            };

            // Safety: the parameters are the documented layout for a timing capture, and the file
            // name pointer is valid for the duration of the call. `PIXBeginCapture` is documented to
            // return `S_FALSE` when it started a capture and an error otherwise - including
            // `E_PENDING` when another capture is already running.
            Some(unsafe { (runtime.begin)(PIX_CAPTURE_TIMING, &parameters) })
        }
    });

    match result {
        // No runtime at all; `runtime()` has already said why.
        None => false,
        Some(S_FALSE) => {
            log::info!("wgpu-mc: PIX timing capture started, written to {}", file.display());
            true
        }
        Some(result) => {
            // The two things a programmatic timing capture needs beyond PIX being installed, in the
            // order the documentation lists them: an elevated process and PIX's capturer in the
            // process. Saying both is more useful than the HRESULT alone.
            log::error!(
                "wgpu-mc: PIX refused to start a timing capture (HRESULT 0x{:08x}). A programmatic \
                 timing capture needs the game to run elevated (PIX asks for administrator for \
                 timing captures), PIX's WinPixTimingCapturer.dll loaded in the process, and no \
                 capture already running",
                result as u32
            );
            false
        }
    }
}

fn end() {
    let result = call_on_its_own_thread(|| {
        let runtime = runtime()?;

        // Safety: `PIXEndCapture` takes whether to discard what was recorded instead of writing it.
        // It is only reached for a capture this side believes it started.
        Some(unsafe { (runtime.end)(0) })
    });

    match result {
        None => {}
        Some(S_FALSE) => log::info!("wgpu-mc: PIX timing capture stopped"),
        Some(result) => log::error!(
            "wgpu-mc: PIX could not stop the capture (HRESULT 0x{:08x})",
            result as u32
        ),
    }
}

/// Runs a PIX call on a thread of its own, and answers what it returned.
///
/// Not on the render thread, and that is not a preference: PIX's runtime initialises COM as it is
/// loaded, and the render thread's COM apartment is already set by the time a frame is being drawn -
/// the call answered `RPC_E_CHANGED_MODE` (0x80010106) every time from there. A fresh thread has no
/// apartment yet, so the runtime can set up whichever one it needs. The call is still made while the
/// render thread waits, so a capture starts and stops at a frame boundary either way.
fn call_on_its_own_thread(body: impl FnOnce() -> Option<i32> + Send + 'static) -> Option<i32> {
    let (sender, receiver) = std::sync::mpsc::channel();

    std::thread::Builder::new()
        .name("wgpu-mc PIX".to_string())
        .spawn(move || {
            let _ = sender.send(body());
        })
        .ok()?;

    match receiver.recv_timeout(std::time::Duration::from_secs(10)) {
        Ok(result) => result,
        Err(error) => {
            log::error!("wgpu-mc: PIX did not answer within ten seconds: {error}");
            None
        }
    }
}

/// Where the capture is written: beside the game's config, numbered so a session's captures do not
/// overwrite each other.
fn capture_file() -> Option<std::path::PathBuf> {
    let directory = crate::RUN_DIRECTORY.get()?;

    let mut index = 1;
    loop {
        let file = directory.join(format!("wgpu-mc-capture-{index}.wpix"));
        if !file.exists() {
            return Some(file);
        }
        index += 1;
    }
}