package dev.birb.wgpu.mixin.core;

import dev.birb.wgpu.WgpuMcMod;
import dev.birb.wgpu.gui.GraphicsPresets;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

/**
 * Keeps the graphics preset this backend cannot draw from being selected or applied.
 *
 * <p>Fabulous is not offered in the quality page - the row is built from
 * {@link GraphicsPresets#offered()} - but the preset is remembered by name in options.txt and the
 * name is read while the options are constructed: the constructor ends with a load, and the game
 * applies whatever came back before the first frame is drawn. So a file written while Fabulous was
 * selected would put the renderer straight back into the transparency post chain it cannot bind,
 * without a screen ever being opened. The value is clamped here instead, and the apply is guarded as
 * well: a clamp can only fix what was loaded, and the guard is what makes "Fabulous is never applied"
 * true no matter which path asks for it.
 */
@Mixin(Options.class)
public abstract class OptionsGraphicsPresetMixin {

    /**
     * Replaces a preset that is not offered with the fallback, once the settings file has been read.
     *
     * <p>The options constructor ends with {@code load()}, so this is the first point at which the
     * stored value is known and the last at which the game has not acted on it: the apply happens
     * later, on the value this leaves behind.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void wgpuMc$clampHiddenGraphicsPreset(Minecraft minecraft, File workingDirectory, CallbackInfo ci) {
        Options options = (Options) (Object) this;
        GraphicsPreset preset = options.graphicsPreset().get();

        if (GraphicsPresets.isOffered(preset)) {
            return;
        }

        GraphicsPreset fallback = GraphicsPresets.fallback();
        options.graphicsPreset().set(fallback);

        WgpuMcMod.LOGGER.warn(
                "wgpu: the '{}' graphics preset is not supported by this renderer yet (it turns on "
                        + "improved transparency, whose post chain samples the depth buffer as a "
                        + "filterable float, which this backend cannot bind); using '{}' instead",
                GraphicsPresets.nameOf(preset),
                GraphicsPresets.nameOf(fallback));
    }

    /**
     * Refuses to apply a preset that is not offered, and puts the fallback in its place.
     *
     * <p>Setting the option here rather than only cancelling is what keeps the row and the value in
     * step: a clamped value is one the quality page has, so its row shows the preset the game is
     * actually on. The set re-enters this method with the fallback, which is offered, so it applies
     * once and the request that came in is cancelled.
     */
    @Inject(method = "applyGraphicsPreset", at = @At("HEAD"), cancellable = true)
    private void wgpuMc$neverApplyAHiddenGraphicsPreset(GraphicsPreset preset, CallbackInfo ci) {
        if (GraphicsPresets.isOffered(preset)) {
            return;
        }

        ((Options) (Object) this).graphicsPreset().set(GraphicsPresets.fallback());
        ci.cancel();
    }
}