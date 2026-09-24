package dev.birb.wgpu.mixin.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 26.1 replaced the direct GLFW buffer swap in {@code RenderSystem.flipFrame} with the
 * {@code GpuBackend} abstraction; {@code flipFrame} now takes a {@code TracyFrameCapture}
 * and there is no longer a {@code GLFW.glfwSwapBuffers} call to redirect.
 *
 * <p>Presenting now happens inside the Rust-side {@code GpuBackend} implementation, so this
 * mixin is kept as the anchor for that work rather than holding a dead {@code @Redirect}.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
}
