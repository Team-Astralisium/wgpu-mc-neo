package dev.birb.wgpu.backend

import dev.birb.wgpu.rust.WgpuNative
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class WgpuBuffer(label: String, val usage: Int, val size: Int) : AutoCloseable {

    private val buffer: Long = WgpuNative.createBuffer(label, usage, size)
    val map: ByteBuffer = MemoryUtil.memCalloc(size)
    private val alive = AtomicBoolean(true)

    constructor(label: String, usage: Int, data: ByteBuffer) : this(label, usage, data.capacity()) {
        val src = data.duplicate()
        src.clear()
        val dst = map.duplicate()
        dst.clear()
        dst.put(src)
        dst.flip()
    }

    fun getWgpuBuffer(): Long = buffer

    fun isClosed(): Boolean = !alive.get()

    fun mappedView(offset: Int = 0, length: Int = size - offset): WgpuMappedView {
        require(offset >= 0 && length >= 0 && offset + length <= size) { "Invalid mapped range: offset=$offset length=$length size=$size" }
        val duplicate = map.duplicate()
        duplicate.position(offset)
        duplicate.limit(offset + length)
        return WgpuMappedView(duplicate.slice())
    }

    override fun close() {
        if (alive.compareAndSet(true, false)) {
            WgpuNative.dropBuffer(buffer)
            MemoryUtil.memFree(map)
        }
    }

    class WgpuMappedView(private val buffer: ByteBuffer) : AutoCloseable {
        fun data(): ByteBuffer = buffer

        override fun close() {}
    }
}
