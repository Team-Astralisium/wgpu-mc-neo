package dev.birb.wgpu.mixin.core;

import dev.birb.wgpu.backend.EarlyWindow;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Defuses NeoForge's early loading screen before the game builds its window.
 *
 * <p>{@code Main.main} is the first place this mod gets to run and the last one before
 * {@code Minecraft}'s constructor ticks that loading screen from the render thread, so it is the
 * only hook with enough time. See {@link EarlyWindow} for what the abort it prevents looks like and
 * why the mod cannot simply switch the screen off instead.
 */
@Mixin(Main.class)
public class EarlyWindowMixin {

    @Inject(method = "main", at = @At("HEAD"))
    private static void wgpuMc$disarmEarlyLoadingScreen(String[] args, CallbackInfo ci) {
        EarlyWindow.disarm();
    }
}