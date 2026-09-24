package dev.birb.wgpu.rust

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.HashMap

/**
 * Where the native library may sit inside the mod jar, in probe order.
 *
 * Deliberately a top-level declaration rather than a member of [WgpuNative]: the object loads the
 * native library from its own `init` block, and Kotlin runs an object's initializers in
 * declaration order, so a property declared below that `init` block is still `null` when the
 * loader reads it. Keeping it here makes the ordering impossible to get wrong.
 */
private val NATIVE_RESOURCE_ROOTS = listOf("META-INF/natives/", "assets/wgpu_mc/natives/")

/**
 * JNI side of the native bridge: shader settings, entity model registration, block-state baking,
 * palettes, panic handling and the renderer handle itself.
 *
 * GPU resource handles (textures, buffers, command encoders, render passes, pipelines) deliberately
 * do **not** live here. They are owned by [WmNative], which binds the C ABI that
 * `rust/wgpu-mc-jni` exports, and every one of them is addressed with the `WmRenderer` pointer
 * returned by [createWmRendererOnWindow].
 *
 * Every declaration in this file must exist as a `#[jni_fn]` in `rust/wgpu-mc-jni`. A declaration
 * without one throws `UnsatisfiedLinkError` - an `Error`, so nothing catches it - the first time it
 * is called, which is why the old GPU-object declarations (`createTexture`, `createBuffer`,
 * `createCommandEncoder`, `presentTexture`, ...) were deleted once the C ABI took over: the Rust
 * side no longer exports those JNI entry points.
 */
object WgpuNative {
	init {
		loadWm()
	}

	@JvmStatic
	fun getClassLoader(): ClassLoader {
		return WgpuNative::class.java.classLoader
	}

	/**
	 * Extracts and loads the native library, then hands the JVM side what Rust needs from it.
	 *
	 * Only two things happen here, and both matter:
	 *
	 *  - `load` puts the cdylib in place and `System.load`s it;
	 *  - [setClassLoader] gives Rust the loader it must use to reach mod classes. `FindClass` from
	 *    an attached native thread resolves against the *system* loader, which cannot see NeoForge's
	 *    transformed classes, so without this every resource lookup panics on an empty cell - and a
	 *    panic on a JNI frame aborts the process rather than unwinding.
	 *
	 * `CoreLib.init()` used to be called here too, asking Rust to route its allocations through
	 * LWJGL's allocator. That shim is commented out on the Rust side
	 * (`rust/wgpu-mc-jni/src/alloc.rs`), so there is no `setAllocator` to call, and the resulting
	 * `UnsatisfiedLinkError` is an `Error` - the `catch (e: Exception)` below does not see it, so it
	 * took the game down during `Minecraft`'s constructor. `CoreLib` has been removed with it.
	 *
	 * Anything added here has to exist as a `#[jni_fn]` in `rust/wgpu-mc-jni`, or the first touch
	 * of this object kills the game.
	 */
	@JvmStatic
	fun loadWm() {
		try {
			load("wgpu_mc_jni", true)
			// This object's own loader, not the thread context loader: it is the one that can
			// resolve the mod's classes, and the only one Rust ever needs to look a class up.
			setClassLoader(WgpuNative::class.java.classLoader)
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
		System.load(resolveNativeLibrary(name, forceOverwrite))
	}

	/**
	 * Extracts the native library [name] out of the mod jar and returns the absolute path of
	 * the extracted file, without loading it.
	 *
	 * The path is what [java.lang.foreign.SymbolLookup.libraryLookup] needs in order to bind
	 * the C ABI surface exposed by the same cdylib that backs the JNI entry points.
	 *
	 * Both the NeoForge-style `META-INF/natives/` location and the Fabric-style
	 * `assets/wgpu_mc/natives/` location are probed, because the Rust build writes to the
	 * latter and the Gradle `copyNatives` task writes to the former.
	 */
	@JvmStatic
	@Throws(IOException::class)
	fun resolveNativeLibrary(name: String, forceOverwrite: Boolean = false): String {
		val mappedName = System.mapLibraryName(name)
		val libraryFile = File("lib", mappedName)
		// Fast path: the prebuilt library sits in the Rust workspace output.
		if (!libraryFile.exists() || forceOverwrite) {
			libraryFile.parentFile?.mkdirs()
			val resourceName = NATIVE_RESOURCE_ROOTS
				.firstOrNull { WgpuNative::class.java.classLoader.getResource(it + mappedName) != null }
				?: throw FileNotFoundException(
					"Could not find lib $mappedName in jar (looked in ${NATIVE_RESOURCE_ROOTS.joinToString()})"
				)

			WgpuNative::class.java.classLoader.getResourceAsStream(resourceName + mappedName).use { input ->
				try {
					Files.copy(input!!, libraryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
				} catch (denied: AccessDeniedException) {
					// Windows keeps a loaded DLL locked until the process that mapped it exits,
					// and two clients sharing a run directory is what that looks like: the copy
					// cannot replace the file the other instance is running. The file that is
					// there is the same library this build produced, so use it rather than
					// refusing to start - the alternative is a crash on launch.
					if (!libraryFile.exists()) throw denied
					dev.birb.wgpu.WgpuMcMod.LOGGER.warn(
						"wgpu: {} is locked by another process; using the copy already on disk",
						libraryFile,
					)
				}
			}
		}
		return libraryFile.absolutePath
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
	external fun setPanicHook()

	@JvmStatic
	external fun registerBlockState(state: Any, blockId: String, stateKey: String)

	@JvmStatic
	external fun getBackend(): String

	/**
	 * [getBackend] that degrades to a placeholder instead of throwing when the renderer has not
	 * been created yet. Blaze3D queries the backend name while building the device info, which can
	 * happen before the first frame is rendered.
	 */
	@JvmStatic
	fun getBackendSafe(): String =
		try {
			getBackend()
		} catch (_: Throwable) {
			"wgpu"
		}

	/**
	 * The adapter behind the renderer: its vendor, its name, the API it is driven through and its
	 * driver, one per line.
	 *
	 * These are the four things `GpuDeviceBackend` is asked for, and on the OpenGL backend they are
	 * `GL_VENDOR`, `GL_RENDERER`, "OpenGL" and `GL_VERSION` - so this is what lets the F3 overlay
	 * name the graphics driver instead of saying "wgpu".
	 */
	@JvmStatic
	external fun getAdapterInfo(): String

	/**
	 * [getAdapterInfo] that degrades to an empty string instead of throwing.
	 *
	 * The same reasoning as [getBackendSafe], plus one more: this is read from the device's
	 * constructor, which runs before the mod's own renderer exists on the very first frame.
	 */
	@JvmStatic
	fun getAdapterInfoSafe(): String =
		try {
			getAdapterInfo()
		} catch (_: Throwable) {
			""
		}

	@JvmStatic
	external fun setWorldRenderState(render: Boolean)

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
	external fun paletteReadPacket(slabIndex: Long, array: ByteArray, currentPosition: Int, blockstateOffsets: LongArray): Int

	@JvmStatic
	external fun registerBlock(name: String)

	@JvmStatic
	external fun clearPalette(l: Long)

	@JvmStatic
	external fun cacheBlockStates()

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
	external fun reloadShaders()

	/**
	 * Creates the renderer (wgpu instance, adapter, device, queue) and returns the pointer to the
	 * `WmRenderer` that Rust keeps alive.
	 *
	 * This pointer is what the whole C ABI in [WmNative] is addressed with, so it is obtained once
	 * per backend and threaded through the device and everything below it.
	 *
	 * The historical `createDevice(long, long, int, int)` declaration is gone: Rust's
	 * `create_device` is a no-argument JNI function, so the four-argument JVM declaration could
	 * never have resolved. [createWmRendererOnWindow] is what the backend calls; this one exists
	 * for a backend that is never presented.
	 *
	 * Returns 0 when no graphics backend could be created, which happens when the backend named
	 * in the renderer config is not usable on this machine *and* the fallback is unavailable too.
	 */
	@JvmStatic
	external fun createWmRenderer(): Long

	/**
	 * [createWmRenderer], but told which window the renderer will present to.
	 *
	 * Rust creates the surface first and then requires the adapter to support it, because an
	 * adapter that cannot present to this window would leave a device that renders perfectly and
	 * never shows anything. Without the handles the renderer can only guess, which is fine for a
	 * backend that is never presented but not for one that is.
	 *
	 * @param display the raw GLFW display handle, or 0 where the platform has none
	 * @param window the raw GLFW window handle
	 * @return the `WmRenderer` pointer, or 0 if no backend could be created
	 */
	@JvmStatic
	external fun createWmRendererOnWindow(display: Long, window: Long): Long
}
