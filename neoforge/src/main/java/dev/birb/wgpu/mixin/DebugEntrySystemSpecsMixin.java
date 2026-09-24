package dev.birb.wgpu.mixin;

import dev.birb.wgpu.WgpuMcMod;
import dev.birb.wgpu.backend.Diagnostics;
import dev.birb.wgpu.rust.WgpuNative;
import net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adds the wgpu status line to the F3 overlay's system block.
 *
 * <p>That block is vanilla's: {@code DebugEntrySystemSpecs#display} asks the {@code GpuDevice} for
 * a vendor, a renderer name, a backend name and a version, and on the OpenGL backend those are the
 * driver's own answers. The port now gives the same four answers from the wgpu adapter - see
 * {@code WgpuDevice} - so the block reads like vanilla again, and what is *not* vanilla is the line
 * saying which wgpu and which API are underneath. It belongs directly under the block it belongs
 * to, which is why it is appended here rather than to the end of the column in
 * {@link DebugHUDMixin}: this way it is part of the same group and cannot drift away from it.
 *
 * <p>The seam is the {@code Collection} argument of {@code addToGroup}, which the method builds
 * once with {@code List.of} - a fresh immutable list, so replacing it is safe.
 */
@Mixin(DebugEntrySystemSpecs.class)
public class DebugEntrySystemSpecsMixin {

    @ModifyArg(
            method = "display",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;addToGroup(Lnet/minecraft/resources/Identifier;Ljava/util/Collection;)V"
            ),
            index = 1
    )
    private static Collection<String> wgpu_mc$addWgpuStatus(Collection<String> vanillaLines) {
        List<String> lines = new ArrayList<>(vanillaLines);

        // Names the adapter wgpu actually picked, which is not necessarily the backend that was
        // requested: an unusable choice falls back to the other one.
        lines.add("Render backend: " + WgpuNative.getBackendSafe());

        if (Diagnostics.isEnabled() && REPORTED.compareAndSet(false, true)) {
            // Diagnostics: the block's exact content, in the order the overlay draws it. A
            // screenshot cannot check this - the system block sits below the profiler section and
            // is usually off the bottom of the window.
            WgpuMcMod.LOGGER.info("wgpu: F3 system block:\n  {}", String.join("\n  ", lines));
        }

        return lines;
    }

    /** Diagnostics are reported once per session, not once per frame. */
    private static final AtomicBoolean REPORTED = new AtomicBoolean();
}
