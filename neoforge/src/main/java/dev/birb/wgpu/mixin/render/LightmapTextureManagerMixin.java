package dev.birb.wgpu.mixin.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightTexture.class)
public class LightmapTextureManagerMixin {
    @Inject(at = @At("RETURN"), method = "<init>")
    private void constructor(GameRenderer renderer, Minecraft client, CallbackInfo ci) {
    }
}