package dev.birb.wgpu.backend

import dev.birb.wgpu.rust.WgpuNative
import java.util.concurrent.atomic.AtomicBoolean

enum class WgpuTextureFormat(val nativeFormatId: Int) {
    RGBA8(0),
    RED8(1),
    RED8I(2),
    DEPTH32(3)
}

class WgpuTexture(
    val usage: Int,
    label: String?,
    textureFormat: WgpuTextureFormat,
    val width: Int,
    val height: Int,
    val mipLevels: Int
) : AutoCloseable {

    val texture: Long
    val debugLabel: String? = label
    private val alive = AtomicBoolean(true)

    init {
        this.texture = WgpuNative.createTexture(textureFormat.nativeFormatId, width, height, usage)
    }

    override fun close() {
        if (alive.compareAndSet(true, false)) {
            WgpuNative.dropTexture(texture)
        }
    }

    fun isClosed(): Boolean = !alive.get()
}
