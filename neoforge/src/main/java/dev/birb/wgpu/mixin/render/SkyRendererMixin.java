package dev.birb.wgpu.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.birb.wgpu.WgpuMcMod;
import dev.birb.wgpu.backend.Diagnostics;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Does not draw the sun or the moon while it is below the horizon.
 *
 * <p>26.1 draws both bodies unconditionally and lets the world hide the one that is down: the sky
 * pass covers the hemisphere <em>above</em> the horizon, and below it there is terrain. Fly high
 * enough that the ground is no longer in that direction and the body that should have set is still
 * there - which is how a player at build height ends up looking at the sun and the moon at once.
 * Vanilla has the same hole; nothing in the sky pass closes it, so this does.
 *
 * <p>The angle is not a parameter of these methods - the caller has already applied it to the pose
 * stack - so the test is on the rotation itself: the body sits at {@code (0, 100, 0)} in model space
 * and the matrix is a rotation about X by the body's angle, whose {@code m11} is that angle's
 * cosine. Positive means the body is above the world horizon, negative means it is below it, and the
 * sky's own transform carries no camera pitch to confuse the two.
 *
 * <p>The sunrise and sunset glow is a separate pass and stays: it is drawn at the horizon where the
 * sun crosses it, and it is what the sky looks like at dawn rather than a body in it.
 */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {

    @Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
    private void wgpu_mc$hideTheSunBelowTheHorizon(float rainBrightness, PoseStack poseStack, CallbackInfo info) {
        if (below(poseStack)) {
            report("sun");
            info.cancel();
        }
    }

    @Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
    private void wgpu_mc$hideTheMoonBelowTheHorizon(MoonPhase moonPhase, float rainBrightness, PoseStack poseStack, CallbackInfo info) {
        if (below(poseStack)) {
            report("moon");
            info.cancel();
        }
    }

    /**
     * Whether the body this pose stack places is under the horizon rather than over it.
     *
     * <p>Not at zero: a body whose <em>centre</em> has just crossed the horizon still has part of its
     * disc above it - the sun's quad is thirty units across at a distance of a hundred, so its edge
     * reaches roughly nine degrees past its centre - and cutting it off at the centre would make the
     * sunset snap. The threshold is a tenth of a degree's worth of the same cosine, which is where
     * both bodies are entirely under the horizon line.
     */
    private static boolean below(PoseStack poseStack) {
        return poseStack.last().pose().m11() < -0.1F;
    }

    /** The last body reported as hidden, so a transition is one line rather than one a frame. */
    private static String reported = null;

    private static void report(String body) {
        if (body.equals(reported)) {
            return;
        }

        reported = body;

        if (Diagnostics.loggingEnabled()) {
            WgpuMcMod.LOGGER.info("wgpu: the {} is below the horizon, so it is not drawn", body);
        }
    }
}