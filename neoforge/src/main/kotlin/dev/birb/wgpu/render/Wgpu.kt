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

	/**
	 * Reports whether the renderer is live.
	 *
	 * The 1.21.1 port used this hook to create the device from the title screen, because that was
	 * the first frame where a window handle existed. In 26.1 that is no longer how the renderer
	 * comes up: `WgpuBackendSelectionMixin` installs [dev.birb.wgpu.backend.WgpuBackend] as the
	 * game's GpuBackend, and `WgpuBackend#createDevice` creates the device and attaches the surface
	 * while the window is still being built. Creating a second renderer here would hand the C ABI
	 * two different `WmRenderer` pointers, so this now only reports state.
	 */
	@JvmStatic
	fun probeNativeBackendOnce() {
		if (nativeBackendProbed) {
			return
		}
		nativeBackendProbed = true

		WgpuMcMod.LOGGER.info("wgpu-mc renderer active: {}", runCatching { WgpuNative.getBackend() }.getOrElse { "not initialised" })
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
		// 26.1 replaced BlockColors#getColor(state, level, pos, tintIndex) with the
		// BlockTintSource pipeline: look up the tint source for the index, then ask it
		// for the world-space colour.
		val state = level.getBlockState(pos)
		val tintSource = client.blockColors.getTintSource(state, tintIndex)
			?: return 0xFFFFFFFF.toInt()
		val color = tintSource.colorInWorld(state, level, pos)
		val r = color shr 16 and 0xFF
		val g = color shr 8 and 0xFF
		val b = color and 0xFF
		return r or (g shl 8) or (b shl 16)
	}
}
