package dev.birb.wgpu.gui.options

import com.google.gson.annotations.SerializedName

/**
 * One entry of the setting schema Rust serialises into `SETTINGS_INFO_JSON`.
 *
 * The names are spelled out rather than left to Gson's default field naming, because Rust writes
 * `desc` / `needs_restart` / `variants` in snake case and Gson would otherwise bind nothing to
 * [needsRestart] and silently report every setting as restart-free.
 */
class RustOptionInfo {
    @field:SerializedName("text")
    var text: String? = null

    @field:SerializedName("desc")
    var desc: String? = null

    @field:SerializedName("needs_restart")
    var needsRestart: Boolean = false

    /**
     * The section this setting belongs under, or null for one that sits in the plain list.
     *
     * Rust decides which settings are debug switches, because it is the side that owns what they
     * do; the screen only decides what a section looks like (a blank row and a sub-heading).
     */
    @field:SerializedName("section")
    var section: String? = null

    @field:SerializedName("variants")
    var variants: Array<String> = emptyArray()

    /**
     * Where each of [variants] is translated, in the same order.
     *
     * The renderer sends both because neither is enough on its own: the display name is what a
     * language that has never heard of the setting falls back to, and the key is what a translation
     * of it is found under.
     */
    @field:SerializedName("variant_keys")
    var variantKeys: Array<String> = emptyArray()
}
