package dev.birb.wgpu.mixin.core;

import dev.birb.wgpu.render.Wgpu;
import dev.birb.wgpu.rust.WgpuNative;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftClientCoreMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void injectWindowHook(GameConfig args, CallbackInfo ci) {
        Wgpu.setMayInitialize(true);
    }

    @Inject(method = {"stop", "scheduleStop"}, at = @At("HEAD"), require = 0)
    private void scheduleRustStop(CallbackInfo ci) {
        WgpuNative.scheduleStop();
    }
}
