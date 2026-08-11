package dev.birb.wgpu.render

import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.entity.EntityState
import dev.birb.wgpu.palette.RustBlockStateAccessor
import dev.birb.wgpu.rust.WgpuNative
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWNativeCocoa
import org.lwjgl.glfw.GLFWNativeWayland
import org.lwjgl.glfw.GLFWNativeWin32
import org.lwjgl.glfw.GLFWNativeX11

object Wgpu {
	@JvmField
	val keyStates: HashMap<Int, Int> = HashMap()

	@Volatile
	private var initialized: Boolean = false

	@Volatile
	private var mayInitialize: Boolean = false

	@Volatile
	private var nativeBackendProbed: Boolean = false

	private var timesTexSubImageCalled: Int = 0

	@JvmStatic
	fun isInitialized(): Boolean {
		return initialized
	}

	@JvmStatic
	fun setInitialized(initialized: Boolean) {
		this.initialized = initialized
	}

	@JvmStatic
	fun isMayInitialize(): Boolean {
		return mayInitialize
	}

	@JvmStatic
	fun setMayInitialize(mayInitialize: Boolean) {
		this.mayInitialize = mayInitialize
	}

	@JvmStatic
	fun probeNativeBackendOnce() {
		if (nativeBackendProbed || !mayInitialize) {
			return
		}
		nativeBackendProbed = true

		try {
			val mcWindow = Minecraft.getInstance().window ?: return
			val windowHandle = mcWindow.window
			if (windowHandle == 0L) {
				return
			}

			val nativeWindow = when (GLFW.glfwGetPlatform()) {
				GLFW.GLFW_PLATFORM_X11 -> GLFWNativeX11.glfwGetX11Window(windowHandle)
				GLFW.GLFW_PLATFORM_WIN32 -> GLFWNativeWin32.glfwGetWin32Window(windowHandle)
				GLFW.GLFW_PLATFORM_COCOA -> GLFWNativeCocoa.glfwGetCocoaWindow(windowHandle)
				GLFW.GLFW_PLATFORM_WAYLAND -> GLFWNativeWayland.glfwGetWaylandWindow(windowHandle)
				else -> 0L
			}

			if (nativeWindow == 0L) {
				WgpuMcMod.LOGGER.warn("Skipped native backend probe: unknown GLFW platform {}", GLFW.glfwGetPlatform())
				return
			}

			WgpuNative.createDevice(windowHandle, nativeWindow, mcWindow.width, mcWindow.height)
			WgpuMcMod.LOGGER.info("Native backend probe result: {}", WgpuNative.getBackend())
		} catch (throwable: Throwable) {
			WgpuMcMod.LOGGER.warn("Native backend probe failed", throwable)
		}
	}

	@JvmStatic
	fun getTimesTexSubImageCalled(): Int {
		return timesTexSubImageCalled
	}

	@JvmStatic
	fun linkRenderDoc() {
		try {
			System.loadLibrary("renderdoc")
		} catch (e: UnsatisfiedLinkError) {
			WgpuMcMod.LOGGER.warn("Error while loading RenderDoc", e)
		}
	}

	@JvmStatic
	fun rustPanic(message: String) {
		WgpuMcMod.LOGGER.error(message)
		throw IllegalStateException(message)
	}

	@JvmStatic
	fun rustDebug(message: String) {
		WgpuMcMod.LOGGER.info("[Engine] {}", message)
	}

	@JvmStatic
	fun helperSetBlockStateIndex(state: Any?, blockstateKey: Int) {
		if (state is RustBlockStateAccessor) {
			state.`wgpu_mc$setRustBlockStateIndex`(blockstateKey)
		}
	}

	@JvmStatic
	fun helperSetPartIndex(entity: String, part: String, index: Int) {
		EntityState.matrixIndices.computeIfAbsent(entity) { HashMap() }[part] = index
	}

	@JvmStatic
	fun helperGetBlockColor(x: Int, y: Int, z: Int, tintIndex: Int): Int {
		val client = Minecraft.getInstance()
		if (client == null || client.level == null) {
			return 0xFFFFFFFF.toInt()
		}
		val level = client.level ?: return 0xFFFFFFFF.toInt()

		val pos = BlockPos(x, y, z)
		val color = client.blockColors.getColor(level.getBlockState(pos), level, pos, tintIndex)
		val r = color shr 16 and 255
		val g = color shr 8 and 255
		val b = color and 255
		return r or (g shl 8) or (b shl 16)
	}
}
