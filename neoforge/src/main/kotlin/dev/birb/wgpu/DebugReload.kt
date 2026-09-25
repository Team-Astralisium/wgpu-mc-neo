package dev.birb.wgpu

import net.minecraft.client.Minecraft
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reloads every resource pack once, some seconds into the run, when `wgpu-reload-resources` exists.
 *
 * This is the trigger the report named and the one thing a test run cannot reach by itself: "after I
 * refreshed, nearly every item icon was gone, and the mob skins too". A refresh is F3+T, which is
 * `Minecraft#reloadResourcePacks`, and everything it breaks - sprite atlases, entity skins, the
 * player skin, the held item - is a texture that is *closed* and re-created while the renderer is
 * holding pointers to it. That is a different code path from a cold start, and one no amount of
 * starting the game fresh will exercise.
 *
 * Client ticks rather than frames, so the reload lands after the world has settled and the first
 * frame dump of the run is a "before" picture. Marker-gated, and fired once.
 */
@EventBusSubscriber(modid = WgpuMcMod.MOD_ID)
object DebugReload {
	private const val MARKER = "wgpu-reload-resources"

	/** How many client ticks to wait, which is twenty seconds at the normal tick rate. */
	private const val TICKS_BEFORE_RELOAD = 400

	private var ticks = 0
	private var done = false

	private val marker: Boolean by lazy {
		Files.exists(Path.of(MARKER)) || Files.exists(Path.of("..", MARKER))
	}

	@JvmStatic
	@SubscribeEvent
	fun onClientTick(event: ClientTickEvent.Post) {
		if (!marker || done) {
			return
		}

		ticks++
		if (ticks < TICKS_BEFORE_RELOAD) {
			return
		}

		done = true
		WgpuMcMod.LOGGER.info("wgpu: the {} marker is present, reloading every resource pack now", MARKER)
		Minecraft.getInstance().reloadResourcePacks()
	}
}