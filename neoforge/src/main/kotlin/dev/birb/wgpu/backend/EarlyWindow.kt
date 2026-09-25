package dev.birb.wgpu.backend

import dev.birb.wgpu.WgpuMcMod
import net.neoforged.fml.earlydisplay.DisplayWindow
import net.neoforged.fml.loading.EarlyLoadingScreenController
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWWindowSizeCallback
import org.lwjgl.opengl.GL

/**
 * NeoForge's early loading screen, and why this mod has to take its window away.
 *
 * FML creates a GLFW window *with an OpenGL context* before a single mod is loaded, draws the splash
 * screen into it, and later hands that same window to the game: `Window#createGlfwWindow` asks
 * [EarlyLoadingScreenController] for it instead of calling `glfwCreateWindow`. Being handed the
 * window is fine for wgpu - the surface is attached to that window's handle and the device comes up
 * normally - but the loading screen keeps a repaint tick installed across the hand over
 * (`DisplayWindow#takeOverGlfwWindow` sets `repaintTick = renderer::renderToScreen`), and NeoForge
 * drives that tick from the render thread while the last of mod loading runs
 * (`ClientModLoader#finish` calls `load(periodicTick)`). The tick is OpenGL:
 * `LoadingScreenRenderer#renderToScreen` -> `GlState#readFromOpenGL` -> `glIsEnabled`. The context it
 * needs is the loading screen's own, created on its own thread; when the render thread does not have
 * it current, the driver call is fatal, and a fatal JNI call is not something FML or this mod can
 * catch:
 *
 * ```
 * FATAL ERROR in native method: Thread[#3,Render thread,5,main]: No context is current ...
 *     at org.lwjgl.opengl.GL11C.glIsEnabled(Native Method)
 *     at net.neoforged.fml.earlydisplay.render.GlState.readFromOpenGL(GlState.java:129)
 *     at net.neoforged.fml.earlydisplay.render.LoadingScreenRenderer.renderToScreen(LoadingScreenRenderer.java:230)
 *     at net.neoforged.fml.earlydisplay.DisplayWindow.periodicTick(DisplayWindow.java:525)
 *     at net.neoforged.neoforge.client.loading.ClientModLoader.finish(ClientModLoader.java:66)
 *     at net.minecraft.client.Minecraft.<init>(Minecraft.java:695)
 * ```
 *
 * A development run never sees this: the build writes `earlyWindowControl = false` into
 * `runs/client/config/fml.toml` (see `configureEarlyWindow` in `build.gradle.kts`). An instance that
 * simply has the mod dropped into it does not have that key, and FML reads its own config long
 * before any mod is loaded, so a mod cannot switch the screen off - it can only defuse the one it is
 * handed, which is what [disarm] and [takeOverForGame] do together:
 *
 * 1. [disarm] takes the window over itself and closes the loading screen. Taking it over stops the
 *    loading screen's own render loop and claims the window's GL context for the calling thread,
 *    which is the render thread; closing it destroys its GL objects and shuts its thread pool down.
 *    Closing also marks it closed, and a closed loading screen is skipped by the very tick that
 *    aborts above, so no OpenGL call is left for the render thread to make.
 * 2. [takeOverForGame] then answers the game's hand-over question with "no", so `createGlfwWindow`
 *    falls through to `glfwCreateWindow` and this mod's `GLFW_NO_API` window is created instead -
 *    the same window a development run gets. The splash's window is hidden at that moment rather
 *    than in [disarm], so it stays on screen for the mod loading it exists to report on.
 *
 * The alternative - keeping FML's window and trying to keep a GL context current on the render
 * thread - was not taken: this mod never otherwise touches OpenGL, and a context that has to be
 * owned by the right thread at the right moment is exactly what the abort above is made of.
 */
object EarlyWindow {
	/** FML's own provider. Anything else is left alone, with a warning. */
	private const val BUILT_IN_PROVIDER = "net.neoforged.fml.earlydisplay.DisplayWindow"

	/** The loading screen's window once [disarm] has taken it over; `0` means "still FML's". */
	@Volatile
	private var window = 0L

	/**
	 * A window-size callback for the taken-over window. FML takes the window over a second time when
	 * it has to display a fatal error, and its code calls `close()` on whatever callback that
	 * returns - a null there would replace the error screen with a `NullPointerException`. LWJGL
	 * frees a callback as soon as it is collected, so this one is held for the life of the process.
	 */
	private val sizeCallback = GLFWWindowSizeCallback.create { _, _, _ -> }

	/**
	 * Called at the head of `Main.main`, which is both the first moment this mod runs and the last
	 * one before the game builds its window. Does nothing when the early loading screen is off,
	 * which is what a development run has.
	 */
	@JvmStatic
	fun disarm() {
		val controller = EarlyLoadingScreenController.current() ?: return

		if (controller !is DisplayWindow) {
			WgpuMcMod.LOGGER.warn(
				"wgpu: the early loading screen is {}, not the {} this mod knows how to take over. " +
					"If the game aborts inside an OpenGL call from the render thread, set " +
					"earlyWindowControl = false in config/fml.toml.",
				controller.javaClass.name,
				BUILT_IN_PROVIDER,
			)
			return
		}

		val handle = try {
			controller.takeOverGlfwWindow()
		} catch (throwable: Throwable) {
			WgpuMcMod.LOGGER.warn("wgpu: could not take over the early loading screen's window", throwable)
			return
		}
		if (handle == 0L) {
			WgpuMcMod.LOGGER.warn("wgpu: the early loading screen has no window to take over")
			return
		}

		window = handle

		// `close` destroys the loading screen's GL objects, so it may only run while the context
		// those objects belong to is current here - and while LWJGL has capabilities for this
		// thread, which is the first thing `LoadingScreenRenderer#close` asks for. The hand over
		// above claims the context; the capabilities are made below. The checks cover the case where
		// the hand over did not take: guessing wrong here would be the abort this file exists to
		// avoid, while skipping costs one thread that keeps the process alive after the game exits.
		if (GLFW.glfwGetCurrentContext() != handle) {
			GLFW.glfwMakeContextCurrent(handle)
		}
		if (GLFW.glfwGetCurrentContext() == handle) {
			try {
				GL.createCapabilities()
				controller.close()
			} catch (throwable: Throwable) {
				WgpuMcMod.LOGGER.warn("wgpu: could not close the early loading screen", throwable)
			}
		} else {
			WgpuMcMod.LOGGER.warn(
				"wgpu: the early loading screen's GL context stayed on its own thread, so its GL " +
					"objects are left to it. Its window is hidden when the game's window is created.",
			)
		}

		GLFW.glfwSetWindowSizeCallback(handle, sizeCallback)?.close()

		WgpuMcMod.LOGGER.info(
			"wgpu: took over the early loading screen's window (0x{})",
			java.lang.Long.toHexString(handle),
		)
	}

	/**
	 * Answers `Window#createGlfwWindow`'s question - "is there an early loading screen to hand the
	 * game's window to?" - for a loading screen that [disarm] has already taken over, and hides the
	 * window it took at the moment the game would have adopted it.
	 */
	@JvmStatic
	fun takeOverForGame(): EarlyLoadingScreenController? {
		val handle = window
		if (handle == 0L) {
			// Not ours: either there is no early loading screen at all, or it is not the built-in
			// one, in which case FML carries on with it exactly as it would without this mod.
			return EarlyLoadingScreenController.current()
		}

		GLFW.glfwHideWindow(handle)
		return null
	}
}