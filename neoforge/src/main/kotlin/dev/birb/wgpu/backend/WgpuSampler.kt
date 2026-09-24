package dev.birb.wgpu.backend

import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import dev.birb.wgpu.rust.WmNative
import java.lang.foreign.MemorySegment
import java.util.OptionalDouble
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [GpuSampler] backed by a `wgpu::Sampler`.
 *
 * Rust currently builds one default sampler per device, so the requested address and filter modes
 * are remembered here purely so the accessors report what Blaze3D asked for rather than `null`.
 */
class WgpuSampler(
    @get:JvmName("device") val device: WgpuDevice,
    private val addressModeU: AddressMode,
    private val addressModeV: AddressMode,
    private val minFilter: FilterMode,
    private val magFilter: FilterMode,
    private val maxAnisotropy: Int,
    private val maxLod: OptionalDouble,
) : GpuSampler() {

    /** Raw `wgpu::Sampler` pointer. */
    @get:JvmName("nativeSampler")
    val nativeSampler: MemorySegment =
        WmNative.createSampler.invokeExact(device.renderer) as MemorySegment

    private val closed = AtomicBoolean(false)

    override fun getAddressModeU(): AddressMode = addressModeU
    override fun getAddressModeV(): AddressMode = addressModeV
    override fun getMinFilter(): FilterMode = minFilter
    override fun getMagFilter(): FilterMode = magFilter
    override fun getMaxAnisotropy(): Int = maxAnisotropy
    override fun getMaxLod(): OptionalDouble = maxLod

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            WmNative.dropSampler.invokeExact(nativeSampler) as Unit
        }
    }
}