package dev.birb.wgpu.rust

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.HashMap

object WgpuNative {
	init {
		loadWm()
	}

	@JvmStatic
	fun getClassLoader(): ClassLoader {
		return WgpuNative::class.java.classLoader
	}

	@JvmStatic
	fun loadWm() {
		try {
			load("wgpu_mc_jni", true)
			CoreLib.init()
			setClassLoader(Thread.currentThread().contextClassLoader)
		} catch (e: Exception) {
			throw IllegalStateException(e)
		}
	}

	private external fun setClassLoader(contextClassLoader: ClassLoader)

	@Suppress("unused")
	private val idLists: HashMap<Any, Long> = HashMap()

	/**
	 * Loads a native library from the resources of this Jar.
	 *
	 * @param name Library to load
	 * @param forceOverwrite Force overwrite the library file
	 * @throws FileNotFoundException Library not found in resources
	 * @throws IOException Cannot move library out of Jar
	 */
	@JvmStatic
	@Throws(IOException::class)
	fun load(name: String, forceOverwrite: Boolean) {
		val mappedName = System.mapLibraryName(name)
		val libDir = File("lib")
		if (!libDir.exists()) {
			libDir.mkdirs()
		}

		val libraryFile = File("lib", mappedName)
		if (forceOverwrite || !libraryFile.exists()) {
			WgpuNative::class.java.classLoader.getResourceAsStream("META-INF/natives/$mappedName").use { input ->
				if (input == null) {
					throw FileNotFoundException("Could not find lib $mappedName in jar")
				}
				Files.copy(input, libraryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
			}
		}
		System.load(libraryFile.absolutePath)
	}

	@JvmStatic
	external fun getSettingsStructure(): String

	@JvmStatic
	external fun getSettings(): String

	@JvmStatic
	external fun sendSettings(settings: String): Boolean

	@JvmStatic
	external fun sendRunDirectory(dir: String)

	@JvmStatic
	external fun getTextureId(identifier: String): Int

	@JvmStatic
	external fun setPanicHook()

	@JvmStatic
	external fun updateWindowTitle(title: String)

	@JvmStatic
	external fun registerBlockState(state: Any, blockId: String, stateKey: String)

	@JvmStatic
	external fun getBackend(): String

	@JvmStatic
	external fun setWorldRenderState(render: Boolean)

	@JvmStatic
	external fun getMouseX(): Double

	@JvmStatic
	external fun getMouseY(): Double

	@JvmStatic
	external fun createPalette(): Long

	@JvmStatic
	external fun destroyPalette(rustPalettePointer: Long)

	@JvmStatic
	external fun paletteIndex(ptr: Long, `object`: Any, index: Int): Int

	@JvmStatic
	external fun paletteSize(rustPalettePointer: Long): Int

	@JvmStatic
	external fun createPaletteStorage(
		copy: LongArray,
		elementsPerLong: Int,
		elementBits: Int,
		maxValue: Long,
		indexScale: Int,
		indexOffset: Int,
		indexShift: Int,
		size: Int
	): Long

	@JvmStatic
	external fun setCursorPosition(x: Double, y: Double)

	@JvmStatic
	external fun setCursorMode(mode: Int)

	@JvmStatic
	external fun paletteReadPacket(slabIndex: Long, array: ByteArray, currentPosition: Int, blockstateOffsets: LongArray): Int

	@JvmStatic
	external fun registerBlock(name: String)

	@JvmStatic
	external fun clearPalette(l: Long)

	@JvmStatic
	external fun destroyPaletteStorage(paletteStorage: Long)

	@JvmStatic
	external fun cacheBlockStates()

	@JvmStatic
	external fun setCamera(x: Double, y: Double, z: Double, renderYaw: Float, renderPitch: Float)

	@JvmStatic
	external fun bakeSection(
		x: Int,
		y: Int,
		z: Int,
		paletteIndices: LongArray,
		storageIndices: LongArray,
		blockIndices: Array<ByteArray>,
		skyIndices: Array<ByteArray>
	)

	@JvmStatic
	external fun setMatrix(type: Int, mat: FloatArray)

	@JvmStatic
	external fun registerEntities(toString: String)

	@JvmStatic
	external fun scheduleStop()

	@JvmStatic
	external fun setAllocator(ptr: Long)

	@JvmStatic
	external fun reloadShaders()

	@JvmStatic
	external fun render(tickDelta: Float, startTime: Long, tick: Boolean)

	@JvmStatic
	external fun createDevice(window: Long, getWindow: Long, w: Int, h: Int)

	@JvmStatic
	external fun createCommandEncoder(): Long

	@JvmStatic
	external fun createTexture(formatId: Int, width: Int, height: Int, usage: Int): Long

	@JvmStatic
	external fun dropTexture(texture: Long)

	@JvmStatic
	external fun createBuffer(s: String, usage: Int, size: Int): Long

	@JvmStatic
	external fun createBufferInit(s: String, usage: Int, data: ByteBuffer): Long

	@JvmStatic
	external fun dropBuffer(buffer: Long)

	@JvmStatic
	external fun getMinUniformAlignment(): Int

	@JvmStatic
	external fun getMaxTextureSize(): Int

	@JvmStatic
	external fun submitEncoders(encodersPtr: Long, encodersCount: Int)

	@JvmStatic
	external fun presentTexture(texturePtr: Long)

	@JvmStatic
	external fun getRenderPassCommandSize(): Long
}
