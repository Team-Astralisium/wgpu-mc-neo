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
 * The address modes and filters are the *sampler's*: a texture that scrolls or tiles - rain and
 * snow, the enchantment glint, flowing water - is sampled past its own edge and folds back only
 * because the sampler says `REPEAT`. They used to be remembered here and dropped on the native
 * side, which meant every texture in the game was clamped and unfiltered; see `create_sampler`.
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
        WmNative.createSampler.invokeExact(
            device.renderer,
            addressModeU.ordinal,
            addressModeV.ordinal,
            minFilter.ordinal,
            magFilter.ordinal,
            maxAnisotropy,
            if (maxLod.isPresent) maxLod.asDouble else NO_LOD_LIMIT,
        ) as MemorySegment

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

    private companion object {
        /** How "no LOD limit" travels across the ABI, which the native side reads as negative. */
        const val NO_LOD_LIMIT = -1.0
    }
}