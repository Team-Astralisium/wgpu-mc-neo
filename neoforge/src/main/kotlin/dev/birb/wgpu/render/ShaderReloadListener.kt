package dev.birb.wgpu.render

import dev.birb.wgpu.BlockCache
import dev.birb.wgpu.backend.PipelinePrecompiler
import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.rust.WgpuNative
import dev.birb.wgpu.rust.WgpuResourceProvider
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener

object ShaderReloadListener : ResourceManagerReloadListener {
	override fun onResourceManagerReload(manager: ResourceManager) {
		WgpuResourceProvider.manager = manager

		// The native block registry is built from the game's blockstate JSONs and the block atlas this
		// reload stitches, so this is the earliest moment it can be asked for; BlockCache waits a few
		// seconds more before it does.
		BlockCache.resourceReloaded()
		if (Wgpu.isInitialized()) {
			try {
				WgpuNative.reloadShaders()
			} catch (t: Throwable) {
				WgpuMcMod.LOGGER.warn("Skipping shader reload because renderer is not ready", t)
			}
		}

		// After the native reload rather than before it, and *outside* the main-screen check above:
		// the pipelines compiled here are the ones the reload just re-read the sources for, and a
		// `--quickPlaySingleplayer` launch is already loading its world by the time this runs, having
		// never shown a title screen for `isInitialized` to be set on.
		PipelinePrecompiler.precompile()
	}
}
