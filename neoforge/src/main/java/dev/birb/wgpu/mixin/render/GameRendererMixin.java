package dev.birb.wgpu.mixin.render;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(at = @At("HEAD"), method = "renderItemInHand", cancellable = true)
    private void renderHand(Camera camera, float partialTick, Matrix4f positionMatrix, CallbackInfo ci) {
        ci.cancel();
    }

}