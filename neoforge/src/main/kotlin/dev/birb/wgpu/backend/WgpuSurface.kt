package dev.birb.wgpu.backend

import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.rust.NativeNames
import dev.birb.wgpu.rust.WmNative
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWNativeCocoa
import org.lwjgl.glfw.GLFWNativeWayland
import org.lwjgl.glfw.GLFWNativeWin32
import org.lwjgl.glfw.GLFWNativeX11
import org.lwjgl.system.MemoryUtil
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandle
import java.util.Objects

/**
 * The swapchain sitting between the wgpu renderer and the OS window.
 *
 * 26.1 has no `GpuSurfaceBackend` (that abstraction arrives in 26.2), so this is not an
 * interface implementation. Instead it owns the native surface and exposes the two operations
 * 26.1 actually needs:
 *
 *  - [blitAndPresent] is called from `CommandEncoderBackend.presentTexture`, which is the hook
 *    26.1's `RenderTarget.blitToScreen()` drives every frame;
 *  - [present] is called from `GpuDeviceBackend.presentFrame` as a no-op safety net, because a
 *    wgpu surface cannot be presented with `glfwSwapBuffers`.
 *
 * @param attached whether Rust already registered the window while it was creating the renderer.
 *   That is the normal case: the window handle is passed to `createWmRendererOnWindow` so the
 *   adapter can be required to support the surface. [attach] exists for the case where the
 *   renderer was created through the plain `createWmRenderer` entry point instead.
 */
class WgpuSurface internal constructor(
    private val device: WgpuDevice,
    surfaceAttached: Boolean = false,
) : AutoCloseable {

    private var attached = surfaceAttached
    private var nextTexture: MemorySegment? = null
    private var configuredWidth = -1
    private var configuredHeight = -1
    private var closed = false
    private var presents = 0L
    private val dumpedFrames = mutableSetOf<Long>()

    /** Registers the window with wgpu. Safe to call repeatedly; only the first call does work. */
    fun attach(windowHandle: Long) {
        if (attached) return

        val (display, window) = windowHandles(windowHandle)
        WmNative.createSurface.invokeExact(device.renderer, display, window) as Unit
        attached = true
    }

    /**
     * Acquires the next swapchain image, blits [source] into it and presents.
     *
     * Reconfigures the surface whenever the window size changed, which is what keeps the
     * swapchain in step with window resizes without needing a resize hook. A swapchain that goes
     * stale for any *other* reason (a move to another monitor, a fullscreen toggle, a driver
     * reset) is recovered inside `acquire_next_texture`, so it does not depend on this.
     */
    fun blitAndPresent(source: WgpuTextureView, width: Int, height: Int) {
        if (closed) return
        if (width <= 0 || height <= 0) return

        configure(width, height)

        val texture = WmNative.acquireNextTexture.invokeExact(device.renderer) as MemorySegment
        if (texture == MemorySegment.NULL) {
            // Either the frame was skipped (the window is occluded) or the swapchain is still
            // being rebuilt. Neither is worth a log line every frame.
            reportPresent(width, height, acquired = false)
            return
        }

        nextTexture = texture
        WmNative.blitFromTexture.invokeExact(device.renderer, source.nativeView, texture) as Unit
        dumpIfRequested(texture, source)
        present()
        reportPresent(width, height, acquired = true)
    }

    /**
     * Writes the presented image out when `-Dwgpu_mc.dumpFrame=<directory>` is set, or when a file
     * named `wgpu-dump-frames` exists in the run directory.
     *
     * Diagnostics: this is how the renderer's own output can be looked at without the window
     * compositor in the way, which is the only way to tell an empty frame apart from one the
     * swapchain never showed. Both ends of the blit are written, because a black window is either
     * "the target was never drawn" (the source is black) or "the blit lost it" (the source is fine
     * and the swapchain image is black).
     *
     * The marker file exists because a `-D` flag has to survive the mod loader's launcher to get
     * here, and that is one more thing that can silently not happen.
     */
    private fun dumpIfRequested(surface: MemorySegment, source: WgpuTextureView) {
        // The dump switch, not the log switch: this writes files, and the marker it exists for is
        // the `wgpu-dump-frames` one.
        if (!Diagnostics.dumpsEnabled()) return

        val frame = presents + 1
        val onDemand = Diagnostics.consumeDumpRequest()
        if (!onDemand && !DUMP_FRAMES.contains(frame)) return
        if (dumpedFrames.contains(frame)) return

        dumpedFrames.add(frame)

        val usage = source.texture.usage()
        if (usage and COPY_SRC != 0) {
            // Flipped: a render target holds the frame the OpenGL way round - see
            // `Diagnostics.dumpTexture` - and this dump exists to be compared with the surface one.
            Diagnostics.dumpTexture(
                device.renderer,
                source.texture.nativeTexture,
                "frame-$frame-source.raw",
                flipRows = true,
            )
        } else {
            WgpuMcMod.LOGGER.warn(
                "wgpu: the main target has usage 0x{} with no COPY_SRC, skipping its frame dump",
                Integer.toHexString(usage),
            )
        }
        dumpTexture(surface, WmNative.dumpSurfaceTextureRgba, "frame-$frame-surface.raw")
    }

    /** Dumps one texture, which must have been created with `COPY_SRC`. */
    private fun dumpTexture(
        texture: MemorySegment,
        entry: MethodHandle,
        name: String,
    ) {
        val path = Diagnostics.path(name)
        entry.invokeExact(device.renderer, texture, NativeNames.utf8(path.toString())) as Boolean
    }

    /**
     * Reports the first present and then one in every [PRESENT_LOG_INTERVAL], so the log says
     * whether frames are reaching the swapchain without drowning in a line per frame.
     */
    private fun reportPresent(width: Int, height: Int, acquired: Boolean) {
        presents++
        if (presents == 1L) {
            WgpuMcMod.LOGGER.info(
                "wgpu: first frame presented, {}x{}, acquired={} attached={}",
                width, height, acquired, attached,
            )
        }

        if (Diagnostics.loggingEnabled() && presents % PRESENT_LOG_INTERVAL == 0L) {
            WgpuMcMod.LOGGER.info(
                "wgpu: present #{} {}x{} acquired={} attached={}",
                presents, width, height, acquired, attached,
            )
            WmNative.logRenderStats.invokeExact() as Unit
        }

        // Diagnostics: a late look at the sprite atlases, which are composed one pass per sprite and
        // therefore have no single upload to dump them from.
        if (Diagnostics.atlasSnapshotDue(presents)) {
            Diagnostics.dumpAtlasSnapshots(device, presents)
        }
    }

    /**
     * Presents the acquired image.
     *
     * Called from `presentFrame`. When [blitAndPresent] already presented this frame this is a
     * no-op, which is the normal case: 26.1 always calls `presentTexture` before `presentFrame`.
     */
    fun present() {
        if (closed) return
        val texture = nextTexture ?: return
        WmNative.presentSurface.invokeExact(device.renderer, texture) as Unit
        nextTexture = null
    }

    private fun configure(width: Int, height: Int) {
        // Without a surface there is nothing to configure, and asking Rust anyway would log a
        // warning every frame.
        if (!attached) return
        if (width == configuredWidth && height == configuredHeight) return
        // Rust negotiates the swapchain format, the present mode and the alpha mode from what the
        // driver reports; the argument only says "use the vsync setting".
        WmNative.configureSurface.invokeExact(
            device.renderer, width, height, PRESENT_MODE_FROM_SETTINGS
        ) as Unit
        configuredWidth = width
        configuredHeight = height
    }

    override fun close() {
        if (closed) return
        closed = true
        if (attached) {
            WmNative.dropSurface.invokeExact(device.renderer) as Unit
            attached = false
        }
    }

    companion object {
        /** `AutoNoVsync`/`Fifo`/`Mailbox` indices as understood by `configure_surface`. */
        private const val PRESENT_MODE_FROM_SETTINGS = 0

        /** How often [reportPresent] logs a successful present while the diagnostics are on. */
        private const val PRESENT_LOG_INTERVAL = 120L

        /**
         * The frames the dump captures. Spread out rather than taken at the start, because the
         * first frames are the loading splash and the interesting ones are the screens that come
         * after it - and a world is only reached a minute or two in, so the later numbers are the
         * ones that catch terrain, clouds and fog rather than a menu.
         */
        private val DUMP_FRAMES = setOf(2L, 300L, 900L, 1800L, 3000L, 4200L, 5400L, 6600L, 7800L, 9000L)

        /** `GpuTexture.USAGE_COPY_SRC`, which a texture needs before it can be read back. */
        private const val COPY_SRC = 2

        /**
         * Translates a GLFW window handle into the display/window pair `raw-window-handle`
         * expects.
         *
         * Public because `WgpuBackend` needs it *before* the renderer exists: passing the handles
         * to `createWmRendererOnWindow` is what lets Rust pick an adapter that can actually
         * present to this window.
         */
        fun windowHandles(windowHandle: Long): Pair<Long, Long> = when (GLFW.glfwGetPlatform()) {
            GLFW.GLFW_PLATFORM_WIN32 -> 0L to GLFWNativeWin32.glfwGetWin32Window(windowHandle)
            GLFW.GLFW_PLATFORM_X11 ->
                GLFWNativeX11.glfwGetX11Display() to GLFWNativeX11.glfwGetX11Window(windowHandle)
            GLFW.GLFW_PLATFORM_COCOA ->
                GLFWNativeCocoa.glfwGetCocoaWindow(windowHandle) to 0L
            GLFW.GLFW_PLATFORM_WAYLAND ->
                0L to GLFWNativeWayland.glfwGetWaylandWindow(windowHandle)
            else -> throw IllegalStateException(
                "wgpu-mc: unsupported GLFW platform ${GLFW.glfwGetPlatform()}"
            )
        }

        /**
         * The window's framebuffer size, in pixels.
         *
         * Read from GLFW rather than from Minecraft's window object because that is what exists at
         * the moment the renderer is created - the device is asked for before the client has a
         * `Window` it can be asked about - and because it is the framebuffer, not the logical size: a
         * scaled window renders at the larger of the two, and a scene's depth texture has to match
         * the attachment the terrain pass renders into.
         *
         * `(0, 0)` for a window that has not been sized yet - a minimised one, or one created before
         * its monitor's scale is known. The renderer takes that as "no size yet" and makes its scene
         * on the first frame that presents instead.
         */
        fun framebufferSize(windowHandle: Long): Pair<Int, Int> {
            val width = MemoryUtil.memAllocInt(1)
            val height = MemoryUtil.memAllocInt(1)

            return try {
                GLFW.glfwGetFramebufferSize(windowHandle, width, height)
                width.get(0) to height.get(0)
            } catch (error: Throwable) {
                WgpuMcMod.LOGGER.warn("wgpu: could not read the window's framebuffer size", error)
                0 to 0
            } finally {
                MemoryUtil.memFree(width)
                MemoryUtil.memFree(height)
            }
        }

        /**
         * Minecraft's own VSync option, which this backend deliberately ignores.
         *
         * On the OpenGL backend it is `glfwSwapInterval`, so it takes effect at once; here the
         * present mode is owned by the renderer's own `vsync` setting, which is applied without a
         * restart by `sendSettings` - see `reapply_present_mode` in `device.rs`. The vanilla option
         * is kept in step *from* that setting (on apply and at client setup), but letting it drive
         * the present mode as well would give two owners for one value, and whichever ran last
         * would win: Minecraft calls this on startup and on a fullscreen toggle, so the player's
         * choice in the Electrum tab would be undone on the next launch.
         */
        fun setVsync(enabled: Boolean) {
            Objects.requireNonNull(enabled)
        }
    }
}
