package dev.birb.wgpu.mixin;

import dev.birb.wgpu.WgpuMcMod;
import dev.birb.wgpu.render.Wgpu;
import dev.birb.wgpu.rust.WgpuNative;
import com.mojang.blaze3d.platform.GlUtil;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public class DebugHUDMixin {

    @Inject(method = "getSystemInformation", at = @At("RETURN"))
    private void getSystemInformation(CallbackInfoReturnable<List<String>> cir) {
        List<String> lines = cir.getReturnValue();
        String backendInfo;
        try {
            backendInfo = WgpuNative.getBackend();
        } catch (Throwable t) {
            backendInfo = "NaN";
        }

        int gpuDriverLineIndex = lines.lastIndexOf(GlUtil.getOpenGLVersion());
        int insertIndex = gpuDriverLineIndex >= 0 ? gpuDriverLineIndex + 1 : Math.min(11, lines.size());
        lines.add(insertIndex, "Render backend: " + backendInfo);

        if (WgpuMcMod.ENTRIES > 0) {
            lines.add("[Neolectrum] texSubImage2D call count: " + Wgpu.getTimesTexSubImageCalled());
            lines.add("[Neolectrum] avg uploading entities: " + (WgpuMcMod.TIME_SPENT_ENTITIES / WgpuMcMod.ENTRIES) + "ns");
        }
    }

}
