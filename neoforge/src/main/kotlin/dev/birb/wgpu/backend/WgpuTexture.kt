package dev.birb.wgpu.backend

import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import dev.birb.wgpu.rust.NativeNames
import dev.birb.wgpu.rust.WmNative
import java.lang.foreign.MemorySegment
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [GpuTexture] backed by a `wgpu::Texture`.
 *
 * The native handle is exposed to the backend package under [getNativeTexture] (the texture view,
 * the encoder and the render pass all hand it back into the C ABI) but never outside it.
 */
class WgpuTexture(
    @get:JvmName("device") val device: WgpuDevice,
    usage: Int,
    label: String,
    format: TextureFormat,
    width: Int,
    height: Int,
    depthOrLayers: Int,
    mipLevels: Int,
) : GpuTexture(usage, label, format, width, height, depthOrLayers, mipLevels) {

    /** Raw `wgpu::Texture` pointer. */
    @get:JvmName("nativeTexture")
    val nativeTexture: MemorySegment

    private val closed = AtomicBoolean(false)

    init {
        nativeTexture = WmNative.createTexture.invokeExact(
            device.renderer,
            WgpuFormat.nativeId(format),
            width,
            height,
            depthOrLayers,
            usage,
            mipLevels,
            NativeNames.utf8(label),
        ) as MemorySegment
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            WmNative.dropTexture.invokeExact(nativeTexture) as Unit
        }
    }

    override fun isClosed(): Boolean = closed.get()
}

/**
 * [GpuTextureView] backed by a `wgpu::TextureView`.
 *
 * The view is what a render pass binds and what gets presented, so [getNativeView] is the handle
 * the encoder and the surface pass back to Rust.
 *
 * The mip range has to reach Rust: a view is a render target only when it covers exactly one mip
 * level, and the level it covers decides both which mip is written and how large the pass renders.
 * 26.1 renders each mip level of a sprite atlas through its own view.
 */
class WgpuTextureView(
    @get:JvmName("device") val device: WgpuDevice,
    @get:JvmName("texture") val texture: WgpuTexture,
    private val baseMipLevel: Int,
    private val viewMipLevels: Int,
) : GpuTextureView(texture, baseMipLevel, viewMipLevels) {

    /** Raw `wgpu::TextureView` pointer. */
    @get:JvmName("nativeView")
    val nativeView: MemorySegment =
        WmNative.createTextureView.invokeExact(
            device.renderer,
            texture.nativeTexture,
            texture.usage(),
            baseMipLevel,
            viewMipLevels,
        ) as MemorySegment

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            WmNative.dropTextureView.invokeExact(nativeView) as Unit
        }
    }

    override fun isClosed(): Boolean = closed.get()
}