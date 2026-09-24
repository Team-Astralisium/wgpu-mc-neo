package dev.birb.wgpu.backend

import com.mojang.blaze3d.buffers.GpuBuffer
import dev.birb.wgpu.rust.NativeNames
import dev.birb.wgpu.rust.WmNative
import org.lwjgl.system.MemoryUtil
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [GpuBuffer] backed by a `wgpu::Buffer`.
 *
 * The native handle is exposed to the rest of the backend package under the accessor name
 * [getNativeBuffer] (the rest of the backend and the Java render pass both read it) but never
 * outside it.
 *
 * Construction goes through [allocate] / [of] rather than overloaded constructors so the primary
 * constructor can take the already-resolved native handle and stay private. [close] is idempotent
 * through one [AtomicBoolean], so a double close during shutdown cannot free the same
 * `wgpu::Buffer` twice.
 */
class WgpuBuffer private constructor(
    bufferUsage: Int,
    bufferSize: Long,
    private val nativeHandle: MemorySegment,
    val label: String,
) : GpuBuffer(bufferUsage, bufferSize) {

    private val closed = AtomicBoolean(false)

    /** Raw `wgpu::Buffer` pointer, read by the encoder and the render pass. */
    @get:JvmName("nativeBuffer")
    val nativeBuffer: MemorySegment
        get() = nativeHandle

    override fun isClosed(): Boolean = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            WmNative.dropBuffer.invokeExact(nativeHandle) as Unit
        }
    }

    companion object {
        private const val ALIGNMENT = 16

        fun allocate(device: WgpuDevice, label: String, usage: Int, size: Long, mapped: Boolean): WgpuBuffer {
            val aligned = roundUp(size)
            val nativeUsage = usage.withCopyDstIfMapped()

            // No arena: the label is encoded once for the name and reused, see `NativeNames`.
            val nativeBuffer = if (mapped) {
                WmNative.allocateGpuBufferMapped.invokeExact(
                    device.renderer,
                    aligned,
                    nativeUsage.toLong(),
                ) as MemorySegment
            } else {
                WmNative.createBuffer.invokeExact(
                    device.renderer,
                    NativeNames.utf8(label),
                    nativeUsage,
                    aligned,
                ) as MemorySegment
            }

            return WgpuBuffer(usage, aligned, nativeBuffer, label)
        }

        fun of(device: WgpuDevice, label: String, usage: Int, data: ByteBuffer): WgpuBuffer {
            val aligned = roundUp(data.capacity().toLong())
            val nativeBuffer = WmNative.createBufferInit.invokeExact(
                device.renderer,
                NativeNames.utf8(label),
                usage.withCopyDstIfMapped(),
                MemorySegment.ofAddress(MemoryUtil.memAddress0(data)),
                data.capacity().toLong(),
            ) as MemorySegment
            return WgpuBuffer(usage, aligned, nativeBuffer, label)
        }

        /**
         * wgpu requires every buffer size to be a multiple of 16 bytes. `Mth.roundToward` is
         * `int`-only in 26.1, so the rounding is done here on the `long` size.
         */
        private fun roundUp(size: Long): Long = (size + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT

        /**
         * A buffer Blaze3D maps for writing must also be a valid copy destination on the wgpu
         * side, otherwise the staging upload in `mapBuffer`'s close handler has nowhere to land.
         */
        private fun Int.withCopyDstIfMapped(): Int =
            if (this and GpuBuffer.USAGE_MAP_WRITE != 0) this or GpuBuffer.USAGE_COPY_DST else this
    }
}