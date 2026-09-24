package dev.birb.wgpu

import com.mojang.logging.LogUtils
import dev.birb.wgpu.rust.WgpuNative
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
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
	}
}
