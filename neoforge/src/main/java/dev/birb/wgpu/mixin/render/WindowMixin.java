package dev.birb.wgpu.mixin.render;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import dev.birb.wgpu.render.Wgpu;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public class WindowMixin {

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"))
    private void noHints(int hint, int value) {
        if (!Wgpu.isInitialized()) {
            GLFW.glfwWindowHint(hint, value);
        }
    }

    @Inject(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J",
                    shift = At.Shift.BEFORE
            )
    )
    private void doTheHints(WindowEventHandler eventHandler, ScreenManager monitorTracker, DisplayData settings, String fullscreenVideoMode, String title, CallbackInfo ci) {
        if (Wgpu.isInitialized()) {
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        }
    }

}
