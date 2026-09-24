package dev.birb.wgpu.backend

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.GpuFence
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.CommandEncoderBackend
import com.mojang.blaze3d.systems.GpuQuery
import com.mojang.blaze3d.systems.RenderPassBackend
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import dev.birb.wgpu.rust.WmNative
import org.lwjgl.system.MemoryUtil
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.util.OptionalDouble
import java.util.OptionalInt
import java.util.OptionalLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier

/** wgpu requires mapped and staged ranges to be 16-byte aligned. */
private const val MAPPING_ALIGNMENT = 16

/**
 * Blaze3D's command encoder, forwarding to a `wgpu::CommandEncoder`.
 *
 * The three Blaze3D overloads of `createRenderPass` collapse onto one private helper, and the
 * "is a pass open" bookkeeping lives here because `wgpu::CommandEncoder` only guarantees that
 * passes are not interleaved.
 *
 * [presentTexture] is the important one for 26.1: `RenderTarget#blitToScreen()` calls it every
 * frame with the main target's colour view, which is exactly the texture the swapchain needs, so
 * the surface blit happens here rather than in `presentFrame`.
 */
class WgpuCommandEncoder(@get:JvmName("device") val device: WgpuDevice) : CommandEncoderBackend {

    /** Raw `wgpu::CommandEncoder` pointer, also read by the Java render pass. */
    @get:JvmName("nativeEncoder")
    val nativeEncoder: MemorySegment =
        WmNative.createCommandEncoder.invokeExact(device.renderer) as MemorySegment

    init {
        // Diagnostics: Minecraft's `CommandEncoder` is not `AutoCloseable` and has no `close`, so
        // the object is dropped to the GC - which costs nothing in the GL backend, whose encoder
        // owns nothing, and leaks a native encoder here. This says who makes them.
        if (Diagnostics.isEnabled()) {
            val site = Throwable().stackTrace
                .drop(1)
                .takeWhile { !it.className.startsWith("dev.birb.wgpu") || it.methodName == "createCommandEncoder" }
                .takeLast(4)
                .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

            if (CREATION_SITES.add(site)) {
                dev.birb.wgpu.WgpuMcMod.LOGGER.info("wgpu: a command encoder was created by {}", site)
            }
        }
    }

    private val closed = AtomicBoolean(false)
    private var inPass = false

    /**
     * Nothing is released when this object is collected.
     *
     * Blaze3D's `CommandEncoder` is not `AutoCloseable` and has no `close`, which is true for the
     * OpenGL backend, whose encoder owns no resource - and used to be false here, where every
     * object owned a `wgpu::CommandEncoder`. Minecraft makes three of those a frame, and at the few
     * hundred frames a second this backend reaches without vsync that was a thousand command
     * buffers a second left to a garbage collector that cannot see native memory: the process went
     * from one gigabyte to twenty in a minute. The pointer below is a token now, and every handle
     * records into the one encoder the native side owns.
     */

    /**
     * Every `(label, format)` an upload through the `ByteBuffer` overload has been seen with.
     *
     * Diagnostics: that overload is handed the source pixels in a `NativeImage.Format` the native
     * side does not get told about, so which format Minecraft actually uploads with decides whether
     * the texture arrives with the channels it is sampled with.
     */
    private val uploadsFromByteBuffers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun createRenderPass(
        label: Supplier<String>,
        colorTexture: GpuTextureView,
        clearColor: OptionalInt,
    ): RenderPassBackend = openPass(label, colorTexture, clearColor, null, OptionalDouble.empty())

    override fun createRenderPass(
        label: Supplier<String>,
        colorTexture: GpuTextureView,
        clearColor: OptionalInt,
        depthTexture: GpuTextureView?,
        clearDepth: OptionalDouble,
    ): RenderPassBackend = openPass(label, colorTexture, clearColor, depthTexture, clearDepth)

    private fun openPass(
        label: Supplier<String>,
        colorTexture: GpuTextureView,
        clearColor: OptionalInt,
        depthTexture: GpuTextureView?,
        clearDepth: OptionalDouble,
    ): RenderPassBackend {
        check(!closed.get()) { "Command encoder is closed" }
        inPass = true
        return WgpuRenderPass(device, this, label, colorTexture, clearColor, depthTexture, clearDepth)
    }

    /** Invoked by [WgpuRenderPass.close] once the pass has been recorded natively. */
    fun onRenderPassClosed() {
        inPass = false
        flush()
    }

    override fun isInRenderPass(): Boolean = inPass

    private fun flush() {
        WmNative.flushEncoder.invokeExact(device.renderer, nativeEncoder) as Unit
    }

    // wgpu has no standalone clear, so each of these records a render pass whose only job is its
    // load op and submits it. They are not optional: `GameRenderer` clears the main colour and
    // depth textures this way before drawing anything, every frame, and with the clear dropped the
    // frame kept whatever the textures already held - a black window once the depth test became a
    // real `LESS_THAN_OR_EQUAL`, because an uncleared depth buffer rejects every fragment.
    //
    // `clear_texture` would not do: it clears to zero, and Minecraft clears depth to 1.0.

    override fun clearColorTexture(colorTexture: GpuTexture, clearColor: Int) {
        WmNative.clearColorTexture.invokeExact(
            nativeEncoder,
            (colorTexture as WgpuTexture).nativeTexture,
            clearColor,
        ) as Unit
        flush()
    }

    override fun clearColorAndDepthTextures(
        colorTexture: GpuTexture,
        clearColor: Int,
        depthTexture: GpuTexture,
        clearDepth: Double,
    ) {
        WmNative.clearColorAndDepthTextures.invokeExact(
            nativeEncoder,
            (colorTexture as WgpuTexture).nativeTexture,
            clearColor,
            (depthTexture as WgpuTexture).nativeTexture,
            clearDepth,
        ) as Unit
        flush()
    }

    override fun clearColorAndDepthTextures(
        colorTexture: GpuTexture,
        clearColor: Int,
        depthTexture: GpuTexture,
        clearDepth: Double,
        regionX: Int,
        regionY: Int,
        regionWidth: Int,
        regionHeight: Int,
    ) {
        WmNative.clearColorAndDepthTexturesRegion.invokeExact(
            nativeEncoder,
            (colorTexture as WgpuTexture).nativeTexture,
            clearColor,
            (depthTexture as WgpuTexture).nativeTexture,
            clearDepth,
            regionX,
            regionY,
            regionWidth,
            regionHeight,
        ) as Unit
        flush()
    }

    override fun clearDepthTexture(depthTexture: GpuTexture, clearDepth: Double) {
        WmNative.clearDepthTexture.invokeExact(
            nativeEncoder,
            (depthTexture as WgpuTexture).nativeTexture,
            clearDepth,
        ) as Unit
        flush()
    }

    /**
     * Stencil is not modelled: the depth attachment is a plain `Depth32Float` with no stencil
     * aspect, and 26.1 only clears stencil for pipelines this renderer never selects.
     */
    override fun clearStencilTexture(texture: GpuTexture, value: Int) = Unit

    override fun writeToBuffer(destination: GpuBufferSlice, data: ByteBuffer) {
        WmNative.writeToBuffer.invokeExact(
            device.renderer,
            (destination.buffer() as WgpuBuffer).nativeBuffer,
            destination.offset(),
            data.remaining().toLong(),
            MemorySegment.ofBuffer(data),
        ) as Unit
        flush()
    }

    /**
     * Hands Blaze3D an aligned CPU staging buffer for [buffer].
     *
     * wgpu exposes no CPU-visible mapping, so the two directions are pushed across the ABI: a write
     * goes through `write_to_buffer` when the view is closed - which is exactly when Blaze3D
     * considers the data final - and a read is filled by `read_buffer` before the view is handed
     * back, because Blaze3D reads it immediately. Leaving the read half out is what made every
     * screenshot black: the staging buffer was allocated and never filled.
     */
    override fun mapBuffer(buffer: GpuBufferSlice, read: Boolean, write: Boolean): GpuBuffer.MappedView {
        val length = buffer.length()
        val staging = MemoryUtil.memAlignedAlloc(MAPPING_ALIGNMENT, length.toInt())
        val nativeBuffer = (buffer.buffer() as WgpuBuffer).nativeBuffer
        val label = (buffer.buffer() as WgpuBuffer).label
        val renderer = device.renderer

        // Diagnostics: a staging buffer that is allocated and never freed is native memory the
        // garbage collector cannot see, which is exactly the shape of "the game asks for memory and
        // never gives it back". The totals say whether every allocation comes back.
        if (Diagnostics.isEnabled()) {
            STAGING_ALLOCATED.addAndGet(length)
            STAGING_OPEN.incrementAndGet()
            if (STAGING_OPEN.get() > STAGING_HIGH_WATER.get()) {
                STAGING_HIGH_WATER.set(STAGING_OPEN.get())
            }
            reportStaging("map")
        }

        if (read) {
            val filled = WmNative.readBuffer.invokeExact(
                renderer,
                nativeBuffer,
                buffer.offset(),
                length,
                MemorySegment.ofAddress(MemoryUtil.memAddress0(staging)),
            ) as Boolean
            if (!filled) {
                dev.birb.wgpu.WgpuMcMod.LOGGER.error(
                    "wgpu: could not read {} bytes at {} back from a buffer; the mapping stays empty",
                    length, buffer.offset(),
                )
            }
        }

        return object : GpuBuffer.MappedView {
            override fun data(): ByteBuffer = staging

            override fun close() {
                try {
                    if (write) {
                        if (Diagnostics.isEnabled()) {
                            reportMappedWrite(label, staging, length)
                        }
                        WmNative.writeToBuffer.invokeExact(
                            renderer,
                            nativeBuffer,
                            buffer.offset(),
                            length,
                            MemorySegment.ofAddress(MemoryUtil.memAddress0(staging)),
                        ) as Unit
                    }
                } finally {
                    MemoryUtil.memAlignedFree(staging)
                    if (Diagnostics.isEnabled()) {
                        STAGING_FREED.addAndGet(length)
                        STAGING_OPEN.decrementAndGet()
                        reportStaging("free")
                    }
                }
            }
        }
    }

    /**
     * Diagnostics: what a `mapBuffer` write is about to put into a buffer whose label looks like a
     * constant buffer, once per second.
     *
     * A mapped write is the one upload path this backend cannot read back afterwards - the buffer
     * is `MAP_WRITE`, and wgpu has no mapping to hand out - so the only place the bytes can be
     * looked at is here, between Blaze3D's last write and the upload. The cloud faces arrive this
     * way, and "the clouds are drawn with the sun's worth of faces but all of them in the player's
     * own cell" is a question about these bytes.
     */
    private fun reportMappedWrite(label: String, staging: ByteBuffer, length: Long) {
        if (!label.startsWith("Cloud")) {
            return
        }

        val now = System.nanoTime()
        val last = MAPPED_WRITES[label] ?: 0L
        if (now - last < 1_000_000_000L) {
            return
        }
        MAPPED_WRITES[label] = now

        val segment = MemorySegment.ofAddress(MemoryUtil.memAddress0(staging)).reinterpret(length)
        val ints = StringBuilder()
        var offset = 0L
        while (offset + 4 <= length && offset < 96) {
            ints.append(segment.get(java.lang.foreign.ValueLayout.JAVA_INT, offset)).append(' ')
            offset += 4
        }

        dev.birb.wgpu.WgpuMcMod.LOGGER.info(
            "wgpu: mapped write to {} ({} bytes), first {} ints: {}",
            label, length, offset / 4, ints.toString().trim(),
        )
    }

    private companion object {
        /** When each mapped write was last reported, so the log stays readable. */
        val MAPPED_WRITES = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /** Mapped-write staging: how much was allocated, freed, and is open right now. */
        private val STAGING_ALLOCATED = java.util.concurrent.atomic.AtomicLong()
        private val STAGING_FREED = java.util.concurrent.atomic.AtomicLong()
        private val STAGING_OPEN = java.util.concurrent.atomic.AtomicInteger()
        private val STAGING_HIGH_WATER = java.util.concurrent.atomic.AtomicInteger()
        private var STAGING_REPORTED_AT = 0L

        /** Reports the staging totals at most once a second, when they changed. */
        fun reportStaging(what: String) {
            val now = System.nanoTime()
            if (now - STAGING_REPORTED_AT < 1_000_000_000L) {
                return
            }
            STAGING_REPORTED_AT = now
            dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                "wgpu: mapped-write staging after {}: {} MB allocated, {} MB freed, {} open (high water {})",
                what,
                STAGING_ALLOCATED.get() / (1024 * 1024),
                STAGING_FREED.get() / (1024 * 1024),
                STAGING_OPEN.get(),
                STAGING_HIGH_WATER.get(),
            )
        }

        /** Where command encoders are created, so each site is reported once. */
        val CREATION_SITES = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /**
         * Sizes of the textures [presentTexture] has been handed, so each one is described once.
         *
         * Shared by every encoder, because Minecraft makes a fresh one each frame and a set per
         * instance would describe the same size once per frame - which is what the log used to do.
         */
        val presented = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }

    override fun copyToBuffer(source: GpuBufferSlice, target: GpuBufferSlice) {
        WmNative.copyBufferToBuffer.invokeExact(
            device.renderer,
            nativeEncoder,
            (source.buffer() as WgpuBuffer).nativeBuffer,
            (target.buffer() as WgpuBuffer).nativeBuffer,
            source.offset(),
            target.offset(),
            source.length(),
        ) as Unit
        flush()
    }

    /**
     * Dumps a texture right after it was uploaded, when its label matches
     * [Diagnostics.DUMP_TEXTURE_LABEL] and the diagnostics are on.
     *
     * Diagnostics. A texture that arrives in the GPU with the wrong channels looks exactly like a
     * shader that samples the right texture the wrong way, and the uploaded bytes are the only
     * thing that tells the two apart.
     */
    private fun dumpUploadIfRequested(texture: WgpuTexture, depthOrLayer: Int) {
        if (!Diagnostics.isEnabled()) return
        if (!texture.label.contains(Diagnostics.DUMP_TEXTURE_LABEL, ignoreCase = true)) return

        val name = "tex-" + texture.label.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-') +
            "-layer$depthOrLayer.raw"
        Diagnostics.dumpTexture(device.renderer, texture.nativeTexture, name)
    }

    override fun writeToTexture(
        destination: GpuTexture,
        source: NativeImage,
        mipLevel: Int,
        depthOrLayer: Int,
        destX: Int,
        destY: Int,
        width: Int,
        height: Int,
        sourceX: Int,
        sourceY: Int,
    ) {
        val upload = NativeImageUpload.region(source, sourceX, sourceY, width, height)
        upload.use {
            WmNative.writeToTexture.invokeExact(
                device.renderer,
                (destination as WgpuTexture).nativeTexture,
                it.segment,
                it.byteSize,
                mipLevel,
                depthOrLayer,
                destX,
                destY,
                width,
                height,
            ) as Unit
        }
        dumpUploadIfRequested(destination as WgpuTexture, depthOrLayer)
        flush()
    }

    override fun writeToTexture(
        destination: GpuTexture,
        source: ByteBuffer,
        format: NativeImage.Format,
        mipLevel: Int,
        depthOrLayer: Int,
        destX: Int,
        destY: Int,
        width: Int,
        height: Int,
    ) {
        if (Diagnostics.isEnabled() && uploadsFromByteBuffers.add("${destination.label} as $format")) {
            dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                "wgpu: byte-buffer upload to {} as {}", destination.label, format
            )
        }
        // The native side takes the bytes as tightly packed RGBA. Any other layout would be
        // uploaded with the channels shifted by a byte per texel, which is not something to let
        // through quietly.
        if (format != NativeImage.Format.RGBA) {
            dev.birb.wgpu.WgpuMcMod.LOGGER.error(
                "wgpu: {} was uploaded as {}, which this backend only stores as RGBA; " +
                    "the texture will be wrong",
                destination.label,
                format,
            )
        }
        WmNative.writeToTexture.invokeExact(            device.renderer,
            (destination as WgpuTexture).nativeTexture,
            MemorySegment.ofBuffer(source),
            source.remaining().toLong(),
            mipLevel,
            depthOrLayer,
            destX,
            destY,
            width,
            height,
        ) as Unit
        dumpUploadIfRequested(destination as WgpuTexture, depthOrLayer)
        flush()
    }

    override fun copyTextureToBuffer(
        source: GpuTexture,
        destination: GpuBuffer,
        offset: Long,
        callback: Runnable,
        mipLevel: Int,
    ) = copyTextureToBuffer(
        source,
        destination,
        offset,
        callback,
        mipLevel,
        0,
        0,
        source.getWidth(mipLevel),
        source.getHeight(mipLevel),
    )

    override fun copyTextureToBuffer(
        source: GpuTexture,
        destination: GpuBuffer,
        offset: Long,
        callback: Runnable,
        mipLevel: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        WmNative.copyTextureToBuffer.invokeExact(
            device.renderer,
            nativeEncoder,
            (source as WgpuTexture).nativeTexture,
            (destination as WgpuBuffer).nativeBuffer,
            offset,
            mipLevel,
            x,
            y,
            width,
            height,
        ) as Unit
        flush()
        callback.run()
    }

    override fun copyTextureToTexture(
        source: GpuTexture,
        destination: GpuTexture,
        mipLevel: Int,
        destX: Int,
        destY: Int,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int,
    ) {
        WmNative.copyTextureToTexture.invokeExact(
            nativeEncoder,
            (source as WgpuTexture).nativeTexture,
            (destination as WgpuTexture).nativeTexture,
            mipLevel,
            destX,
            destY,
            sourceX,
            sourceY,
            width,
            height,
        ) as Unit
        flush()
    }

    override fun presentTexture(texture: GpuTextureView) {
        // Everything recorded so far has to be on the queue before the blit is, or the swapchain
        // gets an image of the target as it was before this frame's draws went in. Pass closes
        // already flush, but the frame that ends with an open pass would present one frame behind,
        // and "one frame behind" is indistinguishable from "a frame nothing drew into".
        flush()

        val view = texture as WgpuTextureView
        val described = "${view.texture.getWidth(0)}x${view.texture.getHeight(0)}"
        if (Diagnostics.isEnabled() && presented.add(described)) {
            dev.birb.wgpu.WgpuMcMod.LOGGER.info("wgpu: presents a {} texture", described)
        }
        device.surface.blitAndPresent(view, view.texture.getWidth(0), view.texture.getHeight(0))
    }

    override fun createFence(): GpuFence = ImmediateFence

    override fun timerQueryBegin(): GpuQuery = NoopQuery

    override fun timerQueryEnd(query: GpuQuery) = Unit

    private object ImmediateFence : GpuFence {
        override fun awaitCompletion(timeoutMs: Long): Boolean = true
        override fun close() = Unit
    }

    private object NoopQuery : GpuQuery {
        override fun getValue(): OptionalLong = OptionalLong.empty()
        override fun close() = Unit
    }
}


/**
 * Scratch CPU buffer used to stage a [NativeImage] region for `write_to_texture`.
 *
 * Kotlin's `use` on this class makes the arena lifetime explicit at the call site, so the native
 * pointer cannot outlive its allocation.
 */
internal class NativeImageUpload private constructor(
    private val arena: Arena,
    @JvmField val segment: MemorySegment,
    @JvmField val byteSize: Long,
) : AutoCloseable {

    override fun close() = arena.close()

    companion object {
        private const val BYTES_PER_PIXEL = 4

        fun region(image: NativeImage, x: Int, y: Int, width: Int, height: Int): NativeImageUpload {
            val arena = Arena.ofConfined()
            val rowBytes = width * BYTES_PER_PIXEL
            val bytes = rowBytes.toLong() * height
            val segment = arena.allocate(bytes)

            // `NativeImage#pixels` hands back **ARGB** - the image stores ABGR and `getPixels`
            // converts on the way out - while an `Rgba8Unorm` texel is the four bytes R, G, B, A in
            // that order. The two were one `ByteBuffer` write apart from each other, and both
            // obvious spellings are wrong: an ARGB int written little-endian stores (B, G, R, A),
            // and written big-endian it stores (A, R, G, B). The channels are therefore taken apart
            // here rather than left to an endianness, which is also what the OpenGL backend does -
            // it passes the image's own ABGR buffer straight to `glTexSubImage2D`, whose
            // little-endian bytes are R, G, B, A.
            //
            // Getting this wrong is not subtle and not local: a blue sky is drawn from an uploaded
            // panorama, so it came out orange, the blue globe icon came out red, and every block,
            // item, GUI and font texture in the game arrived the same way.
            val destination = segment.asByteBuffer()
            val pixels = image.pixels
            for (row in 0 until height) {
                val rowStart = (y + row) * image.width + x
                var offset = row * rowBytes
                for (column in 0 until width) {
                    val argb = pixels[rowStart + column]
                    destination.put(offset, (argb ushr 16).toByte())
                    destination.put(offset + 1, (argb ushr 8).toByte())
                    destination.put(offset + 2, argb.toByte())
                    destination.put(offset + 3, (argb ushr 24).toByte())
                    offset += BYTES_PER_PIXEL
                }
            }

            return NativeImageUpload(arena, segment, bytes)
        }
    }
}