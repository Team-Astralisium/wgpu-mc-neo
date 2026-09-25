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
    /**
     * The native entry point that created this buffer.
     *
     * The three of them do not take the same usage mask: `createBufferInit` used to drop
     * `USAGE_UNIFORM_TEXEL_BUFFER` and `allocateGpuBufferMapped` ignored the mask altogether, so
     * "which call made this buffer" is part of answering what its wgpu flags are.
     */
    val origin: String,
) : GpuBuffer(bufferUsage, bufferSize) {

    private val closed = AtomicBoolean(false)

    /**
     * Bytes the last `mapBuffer` write uploaded into this buffer, or [NEVER_UPLOADED].
     *
     * A mapped write is the one upload this backend cannot see the far side of, and a buffer that
     * is drawn from before what was written into it has arrived - or with less written into it than
     * the draw reads - renders stale faces. [WgpuRenderPass] compares this against the range a draw
     * is about to read, which turns that into a warning instead of a mystery.
     */
    @Volatile
    var lastMappedWrite: Long = NEVER_UPLOADED
        internal set

    /** Raw `wgpu::Buffer` pointer, read by the encoder and the render pass. */
    @get:JvmName("nativeBuffer")
    val nativeBuffer: MemorySegment
        get() = nativeHandle

    /** The wgpu usage flags this buffer carries, for diagnostics. */
    fun wgpuUsages(): Long = WmNative.bufferUsages.invokeExact(nativeHandle) as Long

    override fun isClosed(): Boolean = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            WmNative.dropBuffer.invokeExact(nativeHandle) as Unit
        }
    }

    companion object {
        private const val ALIGNMENT = 16

        /** [WgpuBuffer.lastMappedWrite] before anything has been uploaded through a mapping. */
        const val NEVER_UPLOADED = -1L

        fun allocate(device: WgpuDevice, label: String, usage: Int, size: Long, mapped: Boolean): WgpuBuffer {
            // The native buffer is rounded up to wgpu's alignment; the *reported* size is the one
            // Minecraft asked for. They are not interchangeable, and this is not cosmetic:
            // `GpuBuffer#size` is what vanilla checks a buffer against to decide whether it is still
            // the right size. `MappableRingBuffer#currentBuffer` is compared with
            // `CloudRenderer`'s computed `utbSize` on every frame of every cloud draw, and with the
            // rounded size reported back, 181,824 was never equal to 181,818 - so the cloud's ring of
            // three face buffers was closed and recreated *every frame*, and the buffer the draw
            // bound was one that had just been created and never written. Every face in it decoded
            // to cell (0, 0) facing down: one square of cloud above the player's head, drifting with
            // the cloud offset and snapping back when the cell changed, with the layer itself
            // appearing only on the frames where a rebuild happened to follow the recreation.
            // `gradlew runClient` with the diagnostics on says it in one line - three `Cloud UTB`
            // buffers created per frame - and it is what `reportCloudBuffer` below logs.
            val nativeSize = roundUp(size)

            // No arena: the label is encoded once for the name and reused, see `NativeNames`.
            val nativeBuffer = if (mapped) {
                WmNative.allocateGpuBufferMapped.invokeExact(
                    device.renderer,
                    nativeSize,
                    usage.toLong(),
                ) as MemorySegment
            } else {
                WmNative.createBuffer.invokeExact(
                    device.renderer,
                    NativeNames.utf8(label),
                    usage,
                    nativeSize,
                ) as MemorySegment
            }

            reportCloudBuffer(label, size, nativeSize)

            return WgpuBuffer(
                usage,
                size,
                nativeBuffer,
                label,
                if (mapped) "allocateGpuBufferMapped" else "createBuffer",
            )
        }

        fun of(device: WgpuDevice, label: String, usage: Int, data: ByteBuffer): WgpuBuffer {
            val size = data.capacity().toLong()
            val nativeBuffer = WmNative.createBufferInit.invokeExact(
                device.renderer,
                NativeNames.utf8(label),
                usage,
                MemorySegment.ofAddress(MemoryUtil.memAddress0(data)),
                size,
            ) as MemorySegment

            reportCloudBuffer(label, size, roundUp(size))

            return WgpuBuffer(usage, size, nativeBuffer, label, "createBufferInit")
        }

        /**
         * Diagnostics: a `Cloud*` buffer was created, and at what size.
         *
         * Minecraft rebuilds the cloud mesh every few seconds and reuses three buffers to do it, so a
         * cloud buffer that is created *every frame* is a buffer vanilla thinks is the wrong size -
         * and one that is recreated is one the draw reads before anything has been written into it.
         * Once a second, because the failure this reports is a buffer created 60 times a second.
         */
        private fun reportCloudBuffer(label: String, size: Long, nativeSize: Long) {
            if (!label.startsWith("Cloud") || !Diagnostics.loggingEnabled()) {
                return
            }

            val now = System.nanoTime()
            if (now - lastCloudBufferReport < 1_000_000_000L) {
                return
            }

            lastCloudBufferReport = now
            dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                "wgpu: created buffer {} ({} bytes asked for, {} bytes allocated)",
                label, size, nativeSize,
            )
        }

        @Volatile
        private var lastCloudBufferReport = 0L

        /**
         * wgpu requires every buffer size to be a multiple of 16 bytes. `Mth.roundToward` is
         * `int`-only in 26.1, so the rounding is done here on the `long` size.
         */
        private fun roundUp(size: Long): Long = (size + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT
    }
}