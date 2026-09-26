package dev.birb.wgpu

import dev.birb.wgpu.render.Wgpu
import dev.birb.wgpu.rust.WgpuNative
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Builds the native block registry, once, as soon as the game is ready for it.
 *
 * The Rust side needs this for anything that turns a block state into geometry: `AIR`, and the model
 * behind every state, are both built from it. Two things have to be true first, and neither is
 * obvious from the outside:
 *
 *  - **the native renderer has to exist** ([Wgpu.isRendererLive]), because the registry is built
 *    inside it; and
 *  - **a resource reload has to have happened**, because the bake reads the block atlas that the
 *    reload stitches. Asking for the registry any earlier panics inside the native library - on a
 *    `#[jni_fn]` frame, which means the JVM aborts - so the trigger waits a few seconds after the
 *    first reload rather than racing it.
 *
 * It used to be triggered from the title screen's first frame, which is a place a launch does not
 * always visit: `--quickPlaySingleplayer` goes from the loading screen into the world without ever
 * rendering one, so the registry was never built and the first section offered for baking aborted
 * the JVM. Client ticks are reached on every launch, so the trigger lives here now, and the title
 * screen calls in rather than starting its own thread - which is what keeps this once-only.
 */
@EventBusSubscriber(modid = WgpuMcMod.MOD_ID)
object BlockCache {
	/**
	 * How long to wait after a reload before asking for the registry.
	 *
	 * The atlas is stitched by a reload listener of the game's, and this mod's listener may run
	 * before it; five seconds is far longer than the stitch takes and costs nothing, since the work
	 * happens on its own thread either way.
	 */
	private const val TICKS_AFTER_RELOAD = 100

	private val started = AtomicBoolean(false)

	/** Ticks since the last reload, or -1 while none has happened. */
	@Volatile
	private var sinceReload = -1

	/** Called by the resource-reload listener. */
	@JvmStatic
	fun resourceReloaded() {
		sinceReload = 0
	}

	/**
	 * Starts the cache once everything it needs is there.
	 *
	 * Safe to call from anywhere, as often as anything likes: the work happens on its own thread, and
	 * only the first caller that finds the game ready gets to start it.
	 */
	@JvmStatic
	fun start() {
		if (!Wgpu.isRendererLive()) return
		if (sinceReload < TICKS_AFTER_RELOAD) return
		if (!started.compareAndSet(false, true)) return

		val thread = Thread(Runnable(WgpuNative::cacheBlockStates), "wgpu-mc block cache")
		thread.isDaemon = true
		thread.contextClassLoader = BlockCache::class.java.classLoader
		thread.start()

		WgpuMcMod.LOGGER.info("wgpu: caching block states for the native side")
	}

	@SubscribeEvent
	@JvmStatic
	fun onClientTick(event: ClientTickEvent.Post) {
		val ticks = sinceReload
		if (ticks >= 0) {
			sinceReload = ticks + 1
		}
		start()
	}
}