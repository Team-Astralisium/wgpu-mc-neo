package dev.birb.wgpu

import dev.birb.wgpu.render.ShaderReloadListener
import dev.birb.wgpu.rust.WgpuNative
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent

@Mod(value = WgpuMcMod.MOD_ID, dist = [Dist.CLIENT])
class WgpuMcModClient(modEventBus: IEventBus) {
	init {
		modEventBus.addListener(::onClientSetup)
		modEventBus.addListener(::onRegisterClientReloadListeners)
	}

	private fun onClientSetup(event: FMLClientSetupEvent) {
		event.enqueueWork {
			try {
				WgpuNative.getClassLoader()
				WgpuNative.sendRunDirectory(FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().toString())
				WgpuNative.setPanicHook()
				WgpuMcMod.LOGGER.info("wgpu-mc native bridge initialized")
			} catch (throwable: Throwable) {
				WgpuMcMod.LOGGER.error("Failed to initialize wgpu-mc native bridge", throwable)
			}
		}
	}

	private fun onRegisterClientReloadListeners(event: RegisterClientReloadListenersEvent) {
		event.registerReloadListener(ShaderReloadListener)
	}
}
