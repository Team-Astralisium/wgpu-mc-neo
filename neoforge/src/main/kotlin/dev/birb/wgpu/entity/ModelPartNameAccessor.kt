package dev.birb.wgpu.entity

interface ModelPartNameAccessor {
	fun getName(): String?

	fun setName(name: String?)
}
