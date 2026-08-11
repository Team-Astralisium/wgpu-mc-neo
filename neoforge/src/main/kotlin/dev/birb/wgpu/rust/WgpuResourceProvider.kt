package dev.birb.wgpu.rust

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import java.io.IOException

object WgpuResourceProvider {
	@JvmField
	@Volatile
	var manager: ResourceManager? = null

	@JvmStatic
	fun getResource(path: String): ByteArray {
		val id = try {
			ResourceLocation.parse(path)
		} catch (_: Exception) {
			return ByteArray(0)
		}

		manager?.let { activeManager ->
			val bytes = readResource(activeManager, id)
			if (bytes.isNotEmpty()) {
				return bytes
			}
		}

		val minecraft = Minecraft.getInstance() ?: return ByteArray(0)
		return readResource(minecraft.resourceManager, id)
	}

	private fun readResource(manager: ResourceManager, id: ResourceLocation): ByteArray {
		return try {
			val resource = manager.getResource(id)
			if (resource.isEmpty) {
				return ByteArray(0)
			}

			resource.get().open().use { stream ->
				stream.readAllBytes()
			}
		} catch (_: IOException) {
			ByteArray(0)
		}
	}
}
