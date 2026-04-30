package dev.birb.wgpu.palette

interface RustBlockStateAccessor {
	fun `wgpu_mc$getRustBlockStateIndex`(): Int

	fun `wgpu_mc$setRustBlockStateIndex`(l: Int)
}
