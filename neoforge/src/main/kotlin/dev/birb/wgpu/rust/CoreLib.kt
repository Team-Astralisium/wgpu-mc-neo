package dev.birb.wgpu.rust

import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil

object CoreLib {
	@JvmStatic
	fun init() {
		initAllocator(MemoryUtil.getAllocator())
//		initPanicHandler()
	}

//	private fun initPanicHandler() {
//		CoreLibFFI.setPanicHandler(CALLBACK.address())
//	}

	private fun initAllocator(allocator: MemoryUtil.MemoryAllocator) {
		MemoryStack.stackPush().use { stack ->
			val pfn = stack.mallocPointer(4)
			pfn.put(0, allocator.alignedAlloc)
			pfn.put(1, allocator.alignedFree)
			pfn.put(2, allocator.realloc)
			pfn.put(3, allocator.calloc)

			WgpuNative.setAllocator(pfn.address())
		}
	}
}
