package dev.birb.wgpu.gui;

import net.minecraft.client.GraphicsPreset;

import java.util.List;

/**
 * Which graphics presets this renderer offers, and what a settings file that names another one gets.
 *
 * <p>Fabulous is not offered because this backend cannot draw it yet: the preset turns on improved
 * transparency, which is the transparency post chain, and that chain samples the depth buffer through
 * a filterable float sampler. The depth texture here is Depth32Float, whose sample type is depth
 * rather than float, so the bind group for that chain does not validate - the picture stops at the
 * first frame drawn with the preset on. Nothing about that is a settings problem, so the preset is
 * hidden rather than offered and then refused.
 *
 * <p>Hiding it from the row is only half of it: options.txt remembers the preset by name, and a file
 * written while Fabulous was selected still names it. That name is read while the options are
 * constructed and applied to a dozen options before any screen exists, so the value is clamped there
 * - see {@code OptionsGraphicsPresetMixin}. One class answers both questions so the row and the clamp
 * cannot disagree about what is offered.
 */
public final class GraphicsPresets {

    /**
     * What a preset that is not offered is replaced with.
     *
     * <p>Fancy rather than Custom, because Fabulous is Custom plus the transparency chain: a file that
     * named it also carries the options Fabulous set - a 32 chunk render distance, a 128 chunk cloud
     * range - and Custom would leave those applied. Fancy is what a fresh install has, so a clamped
     * file lands somewhere a player can recognise.
     */
    private static final GraphicsPreset FALLBACK = GraphicsPreset.FANCY;

    /** The presets the quality page cycles through, in the order it cycles them. */
    private static final List<GraphicsPreset> OFFERED = List.of(
            GraphicsPreset.FAST,
            GraphicsPreset.FANCY,
            GraphicsPreset.CUSTOM);

    private GraphicsPresets() {
    }

    /** The presets this renderer can draw, in the order they are offered. */
    public static List<GraphicsPreset> offered() {
        return OFFERED;
    }

    /** Whether {@code preset} is one this renderer can draw. */
    public static boolean isOffered(GraphicsPreset preset) {
        return OFFERED.contains(preset);
    }

    /** What a preset that is not offered is replaced with. */
    public static GraphicsPreset fallback() {
        return FALLBACK;
    }

    /** The name a preset is stored under in options.txt, for the log lines that name one. */
    public static String nameOf(GraphicsPreset preset) {
        return preset.getSerializedName();
    }
}