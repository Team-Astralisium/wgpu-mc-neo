package dev.birb.wgpu.backend

import com.mojang.blaze3d.textures.TextureFormat
import com.mojang.blaze3d.vertex.VertexFormatElement
import dev.birb.wgpu.rust.WmNative

/**
 * Translates Blaze3D formats into the `GpuFormat` discriminants owned by the Rust C ABI.
 *
 * Rust owns the numbering (see `GpuFormat` in `rust/wgpu-mc-jni/bindings.h`), so this is the
 * only place a Blaze3D format becomes a native value. The `when` over `TextureFormat` is
 * exhaustive, so a new Blaze3D texture format shows up as a compile error here rather than as a
 * silent wrong-format fallback at runtime.
 */
object WgpuFormat {

    fun nativeId(format: TextureFormat): Long = when (format) {
        TextureFormat.RGBA8 -> WmNative.RGBA8_UNORM
        TextureFormat.RED8 -> WmNative.R8_UNORM
        TextureFormat.RED8I -> WmNative.R8_UINT
        TextureFormat.DEPTH32 -> WmNative.D32_FLOAT
        TextureFormat.DEPTH24_STENCIL8 -> WmNative.D24_UNORM_S8_UINT
        TextureFormat.DEPTH32_STENCIL8 -> WmNative.D32_FLOAT_S8_UINT
    }

    /**
     * The `GpuFormat` for one vertex element.
     *
     * 26.2 collapsed the three properties that describe a vertex element into a single
     * `com.mojang.blaze3d.GpuFormat`, which is the enum the Rust side was generated from. 26.1
     * still spells an element as `(type, normalized, count)`, so that triple has to be folded back
     * into one value here. Passing only `element.type()`, as an earlier revision did, threw the
     * count and the normalisation flag away: `Color` (four normalised unsigned bytes) and a
     * one-component attribute became the same value, and every integer element arrived in Rust as a
     * single-component format with no mapping.
     *
     * @throws IllegalArgumentException if the element has more than four components, which wgpu
     *   cannot express as a vertex attribute. Minecraft's `MAX_COUNT` is 32, but nothing in the
     *   game - and no format `VertexFormat.Builder` accepts, since it pads to a multiple of four -
     *   comes close to that.
     */
    fun nativeId(element: VertexFormatElement): Long {
        val count = element.count()
        require(count in 1..4) {
            "wgpu-mc: vertex element ${element.type()} x$count cannot be a wgpu vertex attribute"
        }

        val components = count - 1

        return when (element.type()) {
            VertexFormatElement.Type.FLOAT -> FLOAT_32[components]
            VertexFormatElement.Type.UBYTE ->
                if (element.normalized()) UNORM_8[components] else UINT_8[components]
            VertexFormatElement.Type.BYTE ->
                if (element.normalized()) SNORM_8[components] else SINT_8[components]
            VertexFormatElement.Type.USHORT ->
                if (element.normalized()) UNORM_16[components] else UINT_16[components]
            VertexFormatElement.Type.SHORT ->
                if (element.normalized()) SNORM_16[components] else SINT_16[components]
            // GL ignores the normalisation flag for 32-bit integer attributes, and wgpu has no
            // unorm32/snorm32 vertex format to offer if it did not. Minecraft never sets it.
            VertexFormatElement.Type.UINT -> UINT_32[components]
            VertexFormatElement.Type.INT -> SINT_32[components]
        }
    }

    // Indexed by component count - 1. Written out one row per (type, normalisation) pair so the
    // four spellings of each stay visibly in order.

    private val UNORM_8 = longArrayOf(
        WmNative.R8_UNORM, WmNative.RG8_UNORM, WmNative.RGB8_UNORM, WmNative.RGBA8_UNORM,
    )
    private val SNORM_8 = longArrayOf(
        WmNative.R8_SNORM, WmNative.RG8_SNORM, WmNative.RGB8_SNORM, WmNative.RGBA8_SNORM,
    )
    private val UINT_8 = longArrayOf(
        WmNative.R8_UINT, WmNative.RG8_UINT, WmNative.RGB8_UINT, WmNative.RGBA8_UINT,
    )
    private val SINT_8 = longArrayOf(
        WmNative.R8_SINT, WmNative.RG8_SINT, WmNative.RGB8_SINT, WmNative.RGBA8_SINT,
    )

    private val UNORM_16 = longArrayOf(
        WmNative.R16_UNORM, WmNative.RG16_UNORM, WmNative.RGB16_UNORM, WmNative.RGBA16_UNORM,
    )
    private val SNORM_16 = longArrayOf(
        WmNative.R16_SNORM, WmNative.RG16_SNORM, WmNative.RGB16_SNORM, WmNative.RGBA16_SNORM,
    )
    private val UINT_16 = longArrayOf(
        WmNative.R16_UINT, WmNative.RG16_UINT, WmNative.RGB16_UINT, WmNative.RGBA16_UINT,
    )
    private val SINT_16 = longArrayOf(
        WmNative.R16_SINT, WmNative.RG16_SINT, WmNative.RGB16_SINT, WmNative.RGBA16_SINT,
    )

    private val UINT_32 = longArrayOf(
        WmNative.R32_UINT, WmNative.RG32_UINT, WmNative.RGB32_UINT, WmNative.RGBA32_UINT,
    )
    private val SINT_32 = longArrayOf(
        WmNative.R32_SINT, WmNative.RG32_SINT, WmNative.RGB32_SINT, WmNative.RGBA32_SINT,
    )
    private val FLOAT_32 = longArrayOf(
        WmNative.R32_FLOAT, WmNative.RG32_FLOAT, WmNative.RGB32_FLOAT, WmNative.RGBA32_FLOAT,
    )
}
