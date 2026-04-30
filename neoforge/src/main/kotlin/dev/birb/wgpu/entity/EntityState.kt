package dev.birb.wgpu.entity

import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.world.entity.EntityType
import org.joml.Matrix4f

object EntityState {
	@JvmField
	var builderType: EntityType<*>? = null

	@JvmField
	val layers: HashMap<EntityType<*>, EntityModelInfo> = HashMap()

	@JvmField
	var registeringRoot: Boolean = false

	@JvmField
	val entityModelPartStates: HashMap<String, ModelPartState> = HashMap()

	@JvmField
	var instanceOverlay: Int = 0xFFFFFFFF.toInt()

	@JvmField
	val matrixIndices: HashMap<String, HashMap<String, Int>> = HashMap()

	class ModelPartState {
		@JvmField
		var mat: Matrix4f? = null

		@JvmField
		var overlay: Int = 0
	}

	class EntityModelInfo {
		@JvmField
		var root: ModelLayerLocation? = null

		@JvmField
		val features: MutableList<ModelLayerLocation> = ArrayList()
	}
}
