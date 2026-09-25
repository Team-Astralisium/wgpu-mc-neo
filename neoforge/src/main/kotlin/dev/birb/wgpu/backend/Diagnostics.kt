package dev.birb.wgpu.backend

import dev.birb.wgpu.rust.NativeNames
import dev.birb.wgpu.rust.RendererSettings
import dev.birb.wgpu.rust.WmNative
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path

/**
 * The switch behind this backend's diagnostics.
 *
 * A normal run should say what it is doing, not what every pass, pipeline and texture is doing -
 * but when a frame comes out wrong there is no GPU debugger to reach for, and "the GUI is not
 * drawn" and "the GUI is drawn into a texture nobody presents" look identical from the outside.
 * Everything that answers those questions is kept, and gated here:
 *
 *  - the renderer's own `diagnostics` setting, which is the `Debug` switch on the options screen, or
 *  - `-Dwgpu_mc.diagnostics=true`, or
 *  - `WGPU_MC_DIAGNOSTICS=1`, or
 *  - a file named [MARKER] in the run directory, which needs no launcher support.
 *
 * With it on, the backend reports each pipeline once, each render pass once, the frames listed in
 * [WgpuSurface.DUMP_FRAMES] as raw images, and every uploaded texture whose label contains
 * [DUMP_TEXTURE_LABEL]. With it off, none of that runs - and neither do the native side's counters
 * and traces, which are gated on the same setting.
 */
object Diagnostics {

    /** Presence of this file next to the run directory turns diagnostics on. */
    const val MARKER = "wgpu-dump-frames"

    /** The renderer setting that is the switch for everything in this file. */
    const val SETTING = "diagnostics"

    /** Creating this file dumps the next presented frame, then deletes it. */
    const val DUMP_NOW = "wgpu-dump-now"

    /** Where frame and texture dumps land. */
    const val DIRECTORY = "wgpu-frames"

    /** Texture labels containing this (case-insensitively) are dumped after upload. */
    const val DUMP_TEXTURE_LABEL = "panorama"

    /**
     * Render passes whose label starts with this have their colour target dumped when they close.
     *
     * 26.1 builds a sprite atlas by *rendering* every sprite into it - see
     * `TextureAtlas#uploadInitialContents`, which draws each sprite through `animate_sprite_blit`
     * into `mipViews[level]` - so an atlas is not something an upload can be compared against. It
     * is a render result, and the only way to tell "the atlas was never built" from "the atlas was
     * built and the GUI samples the wrong part of it" is to look at the atlas itself.
     */
    const val DUMP_PASS_PREFIX = "Animate "

    /**
     * Passes whose label starts with this are dumped too, after the prefix list above.
     *
     * The clouds are the reason this exists. They are the one thing in the level that is drawn into
     * a texture of its *own* choosing - `CloudRenderer` asks for the main target, or for the
     * separate cloud target when shader transparency is on - and a cloud layer that is drawn
     * somewhere nobody looks at is indistinguishable, in a screenshot of the window, from one that
     * was never drawn. Dumping the pass's own target tells the two apart. The pass is not
     * per-frame: the label is what is keyed on, so one dump per label per run.
     */
    const val DUMP_PASS_PREFIX_2 = "Clouds"

    /**
     * Whether the diagnostics are on.
     *
     * Not a `lazy`: the setting is applied from the options screen, and a dump or a counter that
     * only started working after a restart would be a worse switch than the file it replaced.
     * [refresh] is what re-resolves it, and the read is a volatile field.
     */
    @Volatile
    private var enabled: Boolean = resolve()

    /** Whether the diagnostics are on. */
    @JvmStatic
    fun isEnabled(): Boolean = enabled

    /**
     * Re-resolves [enabled], which the options screen calls when the debug switches are applied.
     *
     * The renderer resolves the same setting on its own side when it receives them, so the two
     * halves of the switch turn over together.
     */
    @JvmStatic
    fun refresh() {
        val value = resolve()
        if (value != enabled) {
            enabled = value
            dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                "wgpu: diagnostics are now {}",
                if (value) "on" else "off",
            )
        }
    }

    /**
     * The switch, from the first of these that answers: a system property, the environment, the
     * renderer's setting, then the marker file.
     *
     * The marker is an override rather than a fallback, so `touch wgpu-dump-frames` still turns
     * diagnostics on for a run started without touching the config, and the property and the
     * environment variable can turn it back *off* - which is what lets a launcher overrule a config
     * file it did not write.
     */
    private fun resolve(): Boolean {
        System.getProperty("wgpu_mc.diagnostics")?.toBoolean()?.let { return it }
        System.getenv("WGPU_MC_DIAGNOSTICS")?.let {
            return it != "0" && !it.equals("false", ignoreCase = true)
        }

        return RendererSettings.bool(SETTING) == true || Files.exists(Path.of(MARKER))
    }

    /**
     * Takes the frame-dump request a marker file represents, if there is one.
     *
     * [WgpuSurface] dumps a handful of fixed frame numbers, which is no use for "look at the frame
     * I am looking at now" - a world is reached after a different number of frames every run, and a
     * screenshot of the window cannot see a GPU debugger's worth of detail. Creating this file
     * dumps the next presented frame instead, and deletes the file, so a request is answered once.
     */
    @JvmStatic
    fun consumeDumpRequest(): Boolean {
        if (!enabled) {
            return false
        }

        val request = Path.of(DUMP_NOW)
        if (!Files.exists(request)) {
            return false
        }

        return try {
            Files.delete(request)
            true
        } catch (error: java.io.IOException) {
            false
        }
    }

    /** Returns a path inside [DIRECTORY] for [name], creating the directory. */
    @JvmStatic
    fun path(name: String): Path {
        val path = Path.of(DIRECTORY, name)
        Files.createDirectories(path.parent)
        return path
    }

    /**
     * Writes a texture out as raw RGBA through [WmNative.dumpTextureRgba].
     *
     * Requires the texture to carry `COPY_SRC`, which `create_texture` always asks for.
     *
     * [flipRows] is for render targets. This backend gives Minecraft OpenGL's clip-space
     * orientation (see the clip-space patch in `rust/wgpu-mc-jni/src/preprocessing.rs`), and an
     * OpenGL render target keeps the first row of the picture at the *bottom* of the texture, so a
     * render target only matches what was on screen once its rows are turned over. Textures that
     * were uploaded rather than rendered into - a sprite atlas after the fix, a panorama face, a
     * font sheet - are already the right way up and are dumped as they are.
     */
    @JvmStatic
    @JvmOverloads
    fun dumpTexture(
        renderer: MemorySegment,
        texture: MemorySegment,
        name: String,
        flipRows: Boolean = false,
    ) {
        val path = path(name)
        WmNative.dumpTextureRgba.invokeExact(
            renderer,
            texture,
            NativeNames.utf8(path.toString()),
            flipRows,
        ) as Boolean
    }

    /** Labels already dumped by [dumpPassTarget], so an atlas is written once, not once per mip. */
    private val dumpedPasses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The dump prefix [label] matches, or null when its target is not dumped at all. */
    private fun dumpPrefixFor(label: String): String? = when {
        label.startsWith(DUMP_PASS_PREFIX) -> DUMP_PASS_PREFIX
        label.startsWith(DUMP_PASS_PREFIX_2) -> DUMP_PASS_PREFIX_2
        else -> null
    }

    /**
     * Whether [dumpPassTarget] would dump this pass, i.e. whether it is worth submitting for.
     *
     * A dump is a readback, and a readback is one of the two places this backend submits - so the
     * caller asks first, because submitting for every pass of the frame would put the submission
     * count back where it was and then some.
     */
    @JvmStatic
    fun dumpsPass(label: String): Boolean {
        val prefix = dumpPrefixFor(label) ?: return false
        return !dumpedPasses.contains("$prefix/$label")
    }

    /**
     * Dumps the colour target of an `Animate ...` or `Clouds` pass once per label.
     *
     * Called by the pass after it has closed - and after the caller has submitted for it, because
     * the pass's own commands are still in the frame's encoder until then, and a dump reads what the
     * GPU has rather than what the encoder is holding.
     */
    @JvmStatic
    fun dumpPassTarget(renderer: MemorySegment, label: String, texture: MemorySegment?) {
        if (texture == null) return

        val prefix = dumpPrefixFor(label) ?: return
        if (!dumpedPasses.add("$prefix/$label")) return

        // Stripping the prefix leaves nothing for a pass that *is* the prefix ("Clouds"), so the
        // label itself is the fallback rather than an empty file name.
        val trimmed = label.removePrefix(prefix)
        val suffix = (if (trimmed.isBlank()) label else trimmed)
            .replace(Regex("[^A-Za-z0-9]+"), "-")
            .trim('-')
        val name = "pass-$suffix.raw"
        // A pass target is a render target, so its rows are the other way up from the image that
        // ends up on screen - see `dumpTexture` - and the point of this dump is to be looked at next
        // to a frame dump.
        dumpTexture(renderer, texture, name, true)
    }
}
