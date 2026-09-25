package dev.birb.wgpu.mixin.render;

import com.mojang.blaze3d.platform.Window;
import dev.birb.wgpu.backend.EarlyWindow;
import net.neoforged.fml.loading.EarlyLoadingScreenController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 26.1 rebuilt {@code Window} on top of {@code com.mojang.blaze3d.systems.GpuBackend}:
 * the constructor now takes a {@code GpuBackend} instead of a {@code ScreenManager}, and
 * the {@code GLFW.glfwWindowHint} calls that the 1.21.1 port used to suppress no longer
 * exist inside it.
 *
 * <p>The window-hint seam moved to {@code GpuBackend#setWindowHints}, which is where this mod asks
 * for {@code GLFW_NO_API} and so gets a window with no OpenGL context at all.
 *
 * <p>The second seam in {@code createGlfwWindow} is FML's early loading screen: the game asks it for
 * a window to adopt, and adopting one means driving a window that was created for OpenGL. This mod
 * answers that question with "no" once it has taken the loading screen over itself, which is what
 * puts the game back on {@code glfwCreateWindow}. See {@link EarlyWindow} for why that is the only
 * workable answer and why the window hints above cannot be it.
 */
@Mixin(Window.class)
public class WindowMixin {

    @Redirect(
            method = "createGlfwWindow",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/loading/EarlyLoadingScreenController;current()Lnet/neoforged/fml/loading/EarlyLoadingScreenController;"
            ),
            require = 1
    )
    private static EarlyLoadingScreenController wgpuMc$doNotAdoptTheEarlyLoadingScreen() {
        return EarlyWindow.takeOverForGame();
    }
}