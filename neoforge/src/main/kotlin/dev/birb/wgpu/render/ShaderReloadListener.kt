package dev.birb.wgpu.render

import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.rust.WgpuNative
import dev.birb.wgpu.rust.WgpuResourceProvider
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener

object ShaderReloadListener : ResourceManagerReloadListener {
	override fun onResourceManagerReload(manager: ResourceManager) {
		WgpuResourceProvider.manager = manager
		if (!Wgpu.isInitialized()) {
			return
		}
		try {
			WgpuNative.reloadShaders()
		} catch (t: Throwable) {
			WgpuMcMod.LOGGER.warn("Skipping shader reload because renderer is not ready", t)
		}
	}
}
