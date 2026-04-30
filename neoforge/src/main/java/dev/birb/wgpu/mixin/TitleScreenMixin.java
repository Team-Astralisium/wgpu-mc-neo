package dev.birb.wgpu.mixin;

import dev.birb.wgpu.WgpuMcMod;
import dev.birb.wgpu.entity.EntityModelUpload;
import dev.birb.wgpu.render.Wgpu;
import dev.birb.wgpu.rust.WgpuNative;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {
    @Unique
    private boolean wgpu_mc$updatedTitle = false;

    @Inject(method = "render", at = @At("HEAD"))
    private void render(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Wgpu.probeNativeBackendOnce();

        if (!wgpu_mc$updatedTitle && Wgpu.isInitialized()) {
            Thread bakeBlocks = new Thread(WgpuNative::cacheBlockStates);
            bakeBlocks.setContextClassLoader(Thread.currentThread().getContextClassLoader());
            bakeBlocks.start();

            Minecraft.getInstance().updateTitle();
            wgpu_mc$updatedTitle = true;

            try {
                EntityModelUpload.uploadEntityModels();
                WgpuMcMod.MAY_INJECT_PART_IDS = true;
                WgpuMcMod.ENTITIES_UPLOADED = true;
            } catch (Throwable throwable) {
                WgpuMcMod.LOGGER.error("Failed to upload entity model definitions", throwable);
            }
        }
    }
}
