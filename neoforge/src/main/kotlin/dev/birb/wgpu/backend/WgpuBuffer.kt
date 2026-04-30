package dev.birb.wgpu.backend

import com.mojang.blaze3d.buffers.GpuBuffer
import dev.birb.wgpu.rust.WgpuNative
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class WgpuBuffer(label: String, usage: Int, size: Int) : GpuBuffer(usage, size) {

    private val buffer: Long
    private val mapShadow: Long
    val map: ByteBuffer = MemoryUtil.memCalloc(size)
    private val alive = AtomicBoolean(true)

    constructor(label: String, usage: Int, data: ByteBuffer) : this(label, usage, data.capacity()) {
        MemoryUtil.memCopy(data, map)
    }

    init {
        this.buffer = WgpuNative.createBuffer(label, usage and (GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_MAP_READ).inv(), size)
    }

    fun getWgpuBuffer(): Long = buffer

    override fun isClosed(): Boolean = !alive.getAcquire()

    override fun close() {
        val wasAlive = alive.compareAndExchange(true, false)
        if (wasAlive) {
            WgpuNative.dropBuffer(buffer)
        } else {
            throw IllegalStateException("wgpu buffer was already dropped")
        }
    }

    class WgpuMappedView(private val buffer: ByteBuffer) : GpuBuffer.MappedView {
        override fun data(): ByteBuffer = buffer
        override fun close() {}
    }
}
