package dev.birb.wgpu

/**
 * Renderer options that the Java side needs to see.
 *
 * This used to also carry `BACKEND`, a hardcoded `Backend.Vulkan` with a `Vulkan`/`DirectX12`/
 * `DirectX11`/`Metal`/`Opengl` enum. It was misleading on two counts: nothing read it, and the
 * renderer ignored it, so the field claimed one backend while the native side created another.
 * A second copy of a setting that only one side honours is worse than no copy at all, so the
 * enum is gone.
 *
 * The graphics backend is now a real setting owned by the native renderer, edited on the
 * "Electrum" page of the video options screen and persisted to `config/wgpu-mc-renderer.json`.
 * Because the wgpu instance, adapter and every resource below them are built for one backend and
 * cannot be replaced while the game runs, changing it takes effect on the next launch; the
 * options screen says so. To ask which backend the *running* renderer ended up on - which is not
 * necessarily the one that was requested, since an unusable choice falls back - use
 * [dev.birb.wgpu.rust.WgpuNative.getBackendSafe].
 */
object Options {
    var HDR: Boolean = false
}
