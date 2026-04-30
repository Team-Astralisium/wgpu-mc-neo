package dev.birb.wgpu.backend

import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.TextureFormat
import dev.birb.wgpu.rust.WgpuNative
import java.util.concurrent.atomic.AtomicBoolean

class WgpuTexture(
    usage: Int,
    label: String?,
    textureFormat: TextureFormat,
    width: Int,
    height: Int,
    mips: Int
) : GpuTexture(usage, label, textureFormat, width, height, mips) {

    val texture: Long
    private val alive = AtomicBoolean(true)

    init {
        val formatId = when (textureFormat) {
            TextureFormat.RGBA8 -> 0
            TextureFormat.RED8 -> 1
            TextureFormat.RED8I -> 2
            TextureFormat.DEPTH32 -> 3
            else -> throw IllegalArgumentException("Unknown texture format: $textureFormat")
        }
        this.texture = WgpuNative.createTexture(formatId, width, height, usage)
    }

    override fun close() {
        val wasAlive = alive.compareAndExchange(true, false)
        if (wasAlive) {
            WgpuNative.dropTexture(texture)
        } else {
            throw IllegalStateException("wgpu texture was already dropped")
        }
    }

    override fun isClosed(): Boolean = !alive.getAcquire()
}
