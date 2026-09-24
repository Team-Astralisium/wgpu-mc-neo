package dev.birb.wgpu.gui.options

import net.minecraft.network.chat.Component

/**
 * The names and descriptions the renderer's settings are shown under.
 *
 * The renderer's schema names a setting rather than wording it: the options screen turns that name
 * into the key `wgpu_mc.option.<name>`, and its description into `wgpu_mc.option.<name>.tooltip`,
 * so the wording lives in `assets/wgpu_mc/lang/` where a resource pack or a translator can reach
 * it. A name no language file has is shown as the schema name made readable, and a description as
 * the English the renderer sent with the setting - which is what keeps a language that has only
 * been half translated, or a pack that ships no language file at all, from showing raw keys.
 */
object OptionText {

    /** Where a setting's name is translated. */
    fun nameKey(setting: String): String = "wgpu_mc.option.$setting"

    /** Where a setting's description is translated. */
    fun tooltipKey(setting: String): String = "wgpu_mc.option.$setting.tooltip"

    /**
     * A setting's name.
     *
     * The fallback is the schema name made readable - `bind_group_cache` as `Bind group cache` -
     * because the renderer has no better text to offer for a name: its own name for a setting is
     * the config key, and `en_us.json` is what an English player actually reads.
     */
    fun name(setting: String): Component =
        Component.translatableWithFallback(nameKey(setting), readable(setting))

    /** A setting's description, falling back to the text the renderer sent with it. */
    fun tooltip(setting: String, fallback: String?): Component =
        Component.translatableWithFallback(tooltipKey(setting), fallback.orEmpty())

    /** One of an enum setting's values, which the renderer names in the language files too. */
    fun value(key: String?, display: String): Component =
        if (key == null) Component.literal(display)
        else Component.translatableWithFallback(key, display)

    /** The key the options screen's `Debug` heading is translated under. */
    fun sectionKey(section: String): String = "wgpu_mc.section.${section.lowercase()}"

    private fun readable(setting: String): String =
        setting.split('_').joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }
}
