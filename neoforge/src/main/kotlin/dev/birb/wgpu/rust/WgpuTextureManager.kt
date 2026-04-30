package dev.birb.wgpu.rust

import net.minecraft.resources.ResourceLocation

class WgpuTextureManager {
	fun getTextureId(id: ResourceLocation): Int {
		return TEXTURES.computeIfAbsent(id) { key ->
			WgpuNative.getTextureId(key.toString())
		}
	}

	private companion object {
		private val TEXTURES = HashMap<ResourceLocation, Int>()
	}
}
