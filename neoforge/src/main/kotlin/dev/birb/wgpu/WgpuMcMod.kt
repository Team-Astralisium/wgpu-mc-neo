package dev.birb.wgpu

import com.mojang.logging.LogUtils
import dev.birb.wgpu.rust.WgpuNative
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import java.util.concurrent.atomic.LongAdder
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.loading.FMLPaths
import org.slf4j.Logger

@Mod(WgpuMcMod.MOD_ID)
class WgpuMcMod(modEventBus: IEventBus) {
	init {
		LOGGER.info("Initializing wgpu-mc NeoForge module")

		// The renderer, its adapter and its swapchain are all built while the window is created,
		// which happens before `FMLClientSetupEvent` - so the settings that choose between Vulkan
		// and DirectX 12 have to be read *here*, or the config file is read after the decision it
		// contains has already been made, and the backend setting silently does nothing.
		if (FMLEnvironment.getDist() == Dist.CLIENT) {
			try {
				WgpuNative.sendRunDirectory(
					FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().toString()
				)
			} catch (throwable: Throwable) {
				LOGGER.error("wgpu-mc could not read its settings before the renderer starts", throwable)
			}
		}
	}

	companion object {
		const val MOD_ID = "wgpu_mc"

		@JvmField
		val LOGGER: Logger = LogUtils.getLogger()

		@JvmField
		var ENTITIES_UPLOADED: Boolean = false

		@JvmField
		var MAY_INJECT_PART_IDS: Boolean = false

		@JvmField
		var TIME_SPENT_ENTITIES: Long = 0

		@JvmField
		var ENTRIES: Long = 0

		/**
		 * The section feed's phases, in nanoseconds, and how many offers they came from.
		 *
		 * The same shape as [TIME_SPENT_ENTITIES]: a running total per phase, with a count to divide
		 * it by, so what the F3 screen shows is an average per section rebuild rather than a number
		 * that depends on how long the game has been running. Filled only while the section-timing
		 * switch is on - see `Diagnostics.sectionTimingEnabled` - and the clock is not read at all
		 * when it is off.
		 *
		 * [java.util.concurrent.atomic.LongAdder] rather than the plain `long` the entity upload uses:
		 * the entity models are uploaded once, from the render thread, while this is written by every
		 * chunk-build thread at once - and `+=` on a plain `long` loses updates under contention, which
		 * is how the first version of this reported a total that went *down* between two reports.
		 *
		 * The three phases are the three things a rebuild does on Minecraft's chunk-build thread:
		 * look up the light of the 27 sections around it, read the block data out of the container
		 * (palette, storage and the comparison that decides whether it has to be sent), and hand the
		 * payload over in one call.
		 */
		@JvmField
		val TIME_SPENT_SECTION_LIGHT: LongAdder = LongAdder()

		@JvmField
		val TIME_SPENT_SECTION_BLOCKS: LongAdder = LongAdder()

		@JvmField
		val TIME_SPENT_SECTION_CALL: LongAdder = LongAdder()

		@JvmField
		val SECTION_OFFERS: LongAdder = LongAdder()

		/** The payload bytes the feed has sent, so the cost of a diff can be read against its size. */
		@JvmField
		val SECTION_PAYLOAD_BYTES: LongAdder = LongAdder()
	}
}
