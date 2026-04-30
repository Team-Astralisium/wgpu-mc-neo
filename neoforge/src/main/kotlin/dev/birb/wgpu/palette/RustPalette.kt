package dev.birb.wgpu.palette

import dev.birb.wgpu.rust.WgpuNative
import net.minecraft.core.IdMapper
import net.minecraft.network.FriendlyByteBuf

class RustPalette(private val idList: IdMapper<*>) {
    var slabIndex: Long = 0
        private set

    fun readPacket(buf: FriendlyByteBuf) {
        slabIndex = WgpuNative.createPalette()
        val index = buf.readerIndex()
        val size = buf.readVarInt()

        val blockstateOffsets = LongArray(size)

        for (i in 0 until size) {
            val obj = idList.byId(buf.readVarInt())
            val accessor = obj as RustBlockStateAccessor
            blockstateOffsets[i] = accessor.`wgpu_mc$getRustBlockStateIndex`().toLong()
        }

        WgpuNative.paletteReadPacket(slabIndex, buf.array(), index, blockstateOffsets)
    }
}
