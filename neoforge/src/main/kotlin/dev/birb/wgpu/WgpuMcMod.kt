package dev.birb.wgpu

import com.mojang.logging.LogUtils
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import org.slf4j.Logger

@Mod(WgpuMcMod.MOD_ID)
class WgpuMcMod(modEventBus: IEventBus) {
	init {
		LOGGER.info("Initializing wgpu-mc NeoForge module")
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
	}
}
