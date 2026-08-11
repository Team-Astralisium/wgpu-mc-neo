package dev.birb.wgpu.entity

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.rust.WgpuNative
import net.minecraft.client.model.geom.LayerDefinitions

object EntityModelUpload {
	private val gson = Gson()

	@JvmStatic
	fun uploadEntityModels() {
		val startNanos = System.nanoTime()
		val models = LayerDefinitions.createRoots()
		val json = JsonObject()
		models.forEach { (layer, definition) ->
			json.add(layer.toString(), gson.toJsonTree(definition))
		}

		WgpuNative.registerEntities(json.toString())

		val elapsedNanos = System.nanoTime() - startNanos
		WgpuMcMod.TIME_SPENT_ENTITIES += elapsedNanos
		WgpuMcMod.ENTRIES++
		WgpuMcMod.LOGGER.info(
			"Uploaded {} model layers to wgpu-mc and processed them in {}ms",
			models.size,
			elapsedNanos / 1_000_000L
		)
	}
}
