package dev.birb.wgpu.mixin.render;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public class SpriteAtlasTextureMixin {

    @Inject(method = "cycleAnimationFrames", cancellable = true, at = @At("HEAD"))
    private void dontTickAnimatedSprites(CallbackInfo ci) {
        ci.cancel();
    }

}