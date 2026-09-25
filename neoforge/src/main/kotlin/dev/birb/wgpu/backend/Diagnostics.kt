package dev.birb.wgpu.backend

import dev.birb.wgpu.rust.NativeNames
import dev.birb.wgpu.rust.RendererSettings
import dev.birb.wgpu.rust.WmNative
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path

/**
 * The two switches behind this backend's diagnostics.
 *
 * A normal run should say what it is doing, not what every pass, pipeline and texture is doing -
 * but when a frame comes out wrong there is no GPU debugger to reach for, and "the GUI is not
 * drawn" and "the GUI is drawn into a texture nobody presents" look identical from the outside.
 * Everything that answers those questions is kept, behind one of two switches:
 *
 *  - **logging** ([loggingEnabled]) writes the *lines*: each pipeline once, each pass once, the draw
 *    and submission counters once a second, the sprite-animation counter, and the uploads and
 *    uniforms the renderer verifies as it goes. The renderer's `logging` setting, which is the
 *    `Debug` switch on the options screen, or a file named [LOGGING_MARKER] in the run directory.
 *  - **dumps** ([dumpsEnabled]) writes the *files*: the frames listed in [WgpuSurface.DUMP_FRAMES]
 *    as raw images, every uploaded texture whose label contains [DUMP_TEXTURE_LABEL], and the sprite
 *    atlases as they are composed. The renderer's `diagnostics` setting, or [DUMP_MARKER].
 *
 * They are separate because they are asked for at different moments and cost different things: a
 * dump is about one specific frame and is usually asked for with [DUMP_NOW] while the game runs,
 * while the log is read over a whole session - so a session that wants the second should not be
 * flooded by the first. Both answer to `-Dwgpu_mc.diagnostics=true` and to `WGPU_MC_DIAGNOSTICS=1`,
 * which is the "make this run diagnosable" override a launcher can pass.
 *
 * With both off, none of that runs - and neither do the native side's counters and traces, which
 * read the same two flags.
 */
object Diagnostics {

    /**
     * Presence of this file next to the run directory turns the *dumps* on.
     *
     * The name is the one this switch has had since it was the only one, so a run started with
     * `touch wgpu-dump-frames` still behaves as it did.
     */
    const val DUMP_MARKER = "wgpu-dump-frames"

    /** The renderer setting behind the dumps. */
    const val DUMP_SETTING = "diagnostics"

    /** Presence of this file next to the run directory turns the *log lines* on. */
    const val LOGGING_MARKER = "wgpu-logging"

    /** The renderer setting behind the log lines. */
    const val LOGGING_SETTING = "logging"

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
     * Whether the renderer's diagnostic *log lines* are written.
     *
     * Not a `lazy`: the setting is applied from the options screen, and a counter that only started
     * working after a restart would be a worse switch than the file it replaced. [refresh] is what
     * re-resolves it, and the read is a volatile field.
     */
    @Volatile
    private var logging: Boolean = resolveLogging()

    /**
     * Whether the renderer's *dumps* are written.
     *
     * The other half of what used to be one switch. They are separate because they cost different
     * things and are asked for at different moments: the log is a line per pipeline plus a line a
     * second and is read while the game runs, while a dump is a file per target and is asked for
     * about one specific frame - so a session that wants the second should not be flooded by the
     * first.
     */
    @Volatile
    private var dumps: Boolean = resolveDumps()

    /** Whether the diagnostic log lines are on. */
    @JvmStatic
    fun loggingEnabled(): Boolean = logging

    /** Whether the dumps are on. */
    @JvmStatic
    fun dumpsEnabled(): Boolean = dumps

    /**
     * The marker file that turns the binding-resolution log on, next to the run directory.
     *
     * Every switch in this file started life as one of these, and this one is worth having as a file
     * too: the question it answers - which name was a shader looking for - is asked while looking at
     * a log, not while clicking through an options screen.
     */
    const val BINDING_MARKER = "wgpu-binding-log"

    /** The renderer setting behind the binding-resolution log. */
    const val BINDING_SETTING = "binding_verbosity"

    /**
     * Whether the verbose log of how binding names were resolved is on.
     *
     * Separate from [isEnabled] because it is a *narrower* switch: the diagnostics report everything
     * the renderer does, and this reports one thing - which of a pipeline's bindings a name resolved
     * to, which names the plan has at all, and which slots a pipeline change left empty. A shader
     * that reads nothing is usually a name that did not resolve, and that is a question worth being
     * able to ask without turning on a line per pass.
     */
    @Volatile
    private var bindings: Boolean = resolveBindings()

    /** Whether the binding-resolution log is on, which the diagnostic log also turns on. */
    @JvmStatic
    fun bindingsEnabled(): Boolean = bindings || logging

    /**
     * Re-resolves both switches, which the options screen calls when the switches are applied.
     *
     * The renderer resolves the same settings on its own side when it receives them, so the two
     * halves of each switch turn over together.
     */
    @JvmStatic
    fun refresh() {
        val loggingValue = resolveLogging()
        if (loggingValue != logging) {
            logging = loggingValue
            dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                "wgpu: logging is now {}",
                if (loggingValue) "on" else "off",
            )
        }

        val dumpsValue = resolveDumps()
        if (dumpsValue != dumps) {
            dumps = dumpsValue
            dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                "wgpu: dumps are now {}",
                if (dumpsValue) "on" else "off",
            )
        }

        val bindingValue = resolveBindings()
        if (bindingValue != bindings) {
            bindings = bindingValue
            dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                "wgpu: the binding resolution log is now {}",
                if (bindingValue) "on" else "off",
            )
        }
    }

    /** The binding-resolution switch: the renderer's setting, or its marker file. */
    private fun resolveBindings(): Boolean =
        RendererSettings.bool(BINDING_SETTING) == true || Files.exists(Path.of(BINDING_MARKER))

    /** The renderer's own switch, whatever it is called on that side. */
    private fun setting(name: String): Boolean = RendererSettings.bool(name) == true

    /**
     * The logging switch, from the first of these that answers: a system property, the environment,
     * the renderer's setting, then the marker file.
     *
     * The marker is an override rather than a fallback, so `touch wgpu-logging` still turns the log
     * on for a run started without touching the config, and the property and the environment
     * variable can turn it back *off* - which is what lets a launcher overrule a config file it did
     * not write.
     */
    private fun resolveLogging(): Boolean {
        System.getProperty("wgpu_mc.diagnostics")?.toBoolean()?.let { return it }
        System.getenv("WGPU_MC_DIAGNOSTICS")?.let {
            return it != "0" && !it.equals("false", ignoreCase = true)
        }

        return setting(LOGGING_SETTING) || Files.exists(Path.of(LOGGING_MARKER))
    }

    /**
     * The dump switch.
     *
     * The same outside override as the log, because a launcher that asks a run to be diagnosable
     * means both, and then the renderer's own setting and the marker it has had all along.
     */
    private fun resolveDumps(): Boolean {
        System.getProperty("wgpu_mc.diagnostics")?.toBoolean()?.let { return it }
        System.getenv("WGPU_MC_DIAGNOSTICS")?.let {
            return it != "0" && !it.equals("false", ignoreCase = true)
        }

        return setting(DUMP_SETTING) || Files.exists(Path.of(DUMP_MARKER))
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
        if (!dumps) {
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

    /**
     * The pipeline family the per-draw binding trace covers, from `wgpu-trace-plan`.
     *
     * Both sides of the trace read the same file, so the JVM's line and the native side's line
     * describe the same draws - and a trace that names a family is what keeps a line per draw per
     * texture slot from being an unusable log.
     */
    @JvmStatic
    fun tracePlanFilter(): String? = traceFilter

    private val traceFilter: String? by lazy {
        try {
            val path = Path.of(TRACE_PLAN)
            if (!dumps || !Files.exists(path)) null
            else Files.readString(path).trim().takeIf { it.isNotEmpty() }
        } catch (error: java.io.IOException) {
            null
        }
    }

    private const val TRACE_PLAN = "wgpu-trace-plan"

    /** How many per-draw trace lines have been written, against the cap. */
    private val tracedDraws = java.util.concurrent.atomic.AtomicLong()

    /** Whether another per-draw trace line is due, which stops at a fixed count. */
    @JvmStatic
    fun traceDrawDue(): Boolean = tracedDraws.incrementAndGet() <= TRACE_DRAW_LIMIT

    private const val TRACE_DRAW_LIMIT = 200_000L

    /**
     * The label of the texture view at [address], for the per-draw trace.
     *
     * A draw call's texture slot holds a pointer, and the label is the only thing that makes the
     * pointer readable - so the mapping is kept here, where the JVM hands a view over, and read back
     * when a draw is recorded. It is the same label the native side records when it creates the view
     * from the same texture, which is what lets the two traces be compared line by line.
     */
    private val viewLabels = java.util.concurrent.ConcurrentHashMap<Long, String>()

    @JvmStatic
    fun noteView(address: Long, label: String) {
        if (viewLabels.size > 8192) {
            // A view a draw still names must keep its label, so the map is not cleared wholesale:
            // the oldest entries are the ones nothing has bound in a long time, and losing one only
            // makes a trace line say `<unknown view>`.
            viewLabels.clear()
        }
        viewLabels[address] = label
    }

    @JvmStatic
    fun viewLabelOf(address: Long): String =
        viewLabels[address] ?: "<unknown view 0x%x>".format(address)

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
     * Whether a texture dump under [name] is still due, so a caller can decide whether it is worth
     * submitting for.
     *
     * The GUI item atlas is the case this exists for: it is dumped after the first pass that draws
     * into it, and the caller has to know that before it submits - a submission per pass is what
     * this backend deliberately stopped doing.
     */
    @JvmStatic
    fun wantsTextureDump(name: String): Boolean = dumps && !dumpedTextures.contains(name)

    /** How many passes have drawn into the GUI item atlas this run. */
    private val itemAtlasPasses = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Whether the GUI item atlas should be dumped now.
     *
     * Not on the first pass: that one draws into an atlas the *previous* frame filled, so the file
     * would hold one item. The atlas is kept between frames and its slots are only re-drawn when they
     * go stale, so by the third pass of the run it holds a frame's worth of icons - which is the
     * thing that says whether "the icons are missing" is an empty atlas or a GUI that blits the wrong
     * part of a full one.
     */
    @JvmStatic
    fun itemAtlasDumpDue(): Boolean {
        if (!dumps) return false
        if (itemAtlasPasses.incrementAndGet() < 3)
            return false
        return dumpedTextures.add(ITEM_ATLAS_DUMP)     // 只有第一次真正 due 才返回 true
    }

    private const val ITEM_ATLAS_DUMP = "pass-ui-items-atlas"

    /** Names already dumped by [dumpTextureIfWanted] and its callers, once per run. */
    private val dumpedTextures = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Dumps a texture whose label contains [wanted], once, as [name].
     *
     * A texture that is *rendered* rather than uploaded has no upload to hang a dump on and no pass
     * label of its own: the GUI item atlas is filled by the feature renderer, through
     * `RenderSystem#outputColorTextureOverride`, and its target is the texture itself. The label is
     * the only thing that identifies it, so the label is what this matches.
     */
    @JvmStatic
    fun dumpTextureIfWanted(texture: WgpuTexture, wanted: String, name: String) {
        if (!dumps || !texture.label.contains(wanted, ignoreCase = true)) return
        if (!dumpedTextures.add(name)) return

        dumpTexture(texture.device.renderer, texture.nativeTexture, "$name.raw", true)
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

    /**
     * Frames at which every sprite atlas is dumped again, as a time series.
     *
     * `dumpPassTarget` writes an atlas out after the *first* pass that draws into it, and an atlas is
     * composed by one pass per sprite - so that file holds one sprite and empty space, which says
     * nothing about whether the composition put each sprite where the game thinks it is. These are
     * late enough that the initial upload is finished (Minecraft composes every sprite before the
     * first world frame) and spread far enough apart to catch an atlas that comes apart later, which
     * is what "the icons were fine until I refreshed them" would look like.
     */
    private val ATLAS_SNAPSHOT_PRESENTS = longArrayOf(60, 600, 3600)

    /**
     * Which labels the snapshots cover.
     *
     * The atlases are what the item and block pictures come from, and "Entity Outline" is the target
     * every entity is drawn into when it glows and which is then blended over the frame - so it is
     * the difference between "the mobs are missing" and "the mobs are there, under a white copy of
     * themselves", and those two look identical in a frame dump.
     *
     * "Main / Depth" is the same question one layer further down: an entity that is drawn and then
     * fails the depth test leaves *nothing* in the colour target, and the only thing that says
     * whether the draw happened at all is whether its depth is in there. And "textures/entity" is the
     * other end - a skin that never made it into its texture is an invisible model with a perfectly
     * correct binding, which is what the per-draw trace says this renderer has.
     */
    private val ATLAS_SNAPSHOT_LABELS = listOf(
        "atlas/blocks",
        "atlas/items",
        "UI items atlas",
        "Entity Outline",
        "Main / Depth",
        "textures/entity",
    )

    /** Whether [presents] is one of the frames an atlas snapshot is due at. */
    @JvmStatic
    fun atlasSnapshotDue(presents: Long): Boolean = dumps && ATLAS_SNAPSHOT_PRESENTS.contains(presents)

    /**
     * Dumps every live texture whose label contains one of [ATLAS_SNAPSHOT_LABELS].
     *
     * Called from the present path, which is the one place that runs once a frame no matter what the
     * frame drew, and which has already submitted - a dump reads what the GPU has, not what the
     * encoder is still holding.
     */
    @JvmStatic
    fun dumpAtlasSnapshots(device: WgpuDevice, presents: Long) {
        for (wanted in ATLAS_SNAPSHOT_LABELS) {
            for (texture in device.texturesMatching(wanted)) {
                val name = texture.label.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
                dumpTexture(
                    device.renderer,
                    texture.nativeTexture,
                    "atlas-$name-$presents.raw",
                    true,
                )
            }
        }
    }
}
