package dev.birb.wgpu.mixin.render;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class InGameHudMixin {

    // 26.1 renamed Gui#renderVignette to Gui#extractVignette and GuiGraphics to GuiGraphicsExtractor.
    @Inject(method = "extractVignette", cancellable = true, at = @At("HEAD"))
    private void pleaseDoNotTheVignette(GuiGraphicsExtractor context, Entity entity, CallbackInfo ci) {
        ci.cancel();
    }

}