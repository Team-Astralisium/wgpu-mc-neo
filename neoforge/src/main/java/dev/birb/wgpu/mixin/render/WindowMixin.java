package dev.birb.wgpu.mixin.render;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 26.1 rebuilt {@code Window} on top of {@code com.mojang.blaze3d.systems.GpuBackend}:
 * the constructor now takes a {@code GpuBackend} instead of a {@code ScreenManager}, and
 * the {@code GLFW.glfwWindowHint} calls that the 1.21.1 port used to suppress no longer
 * exist inside it.
 *
 * <p>The window-hint seam moved to {@code GpuBackend#setWindowHints}. Once the Rust side
 * implements {@code GpuBackend} (see the Fabric 26.x {@code WgpuBackend}), suppressing the
 * client API hint belongs there and this mixin can disappear.
 */
@Mixin(Window.class)
public class WindowMixin {
}
