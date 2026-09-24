package dev.birb.wgpu.mixin.core;

import com.mojang.blaze3d.systems.GpuBackend;
import dev.birb.wgpu.backend.WgpuBackend;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Installs the wgpu backend instead of Blaze3D's OpenGL backend.
 *
 * <p>26.1 builds the candidate list inline, right before the window is created:
 *
 * <pre>
 *   GpuBackend[] backends = new GpuBackend[]{new GlBackend()};
 *   ...
 *   windowCandidate = new Window(this, displayData, ..., backend);
 * </pre>
 *
 * <p>The obvious seam - replacing the {@code new GlBackend()} expression - does not work:
 * {@code @ModifyExpressionValue} at {@code NEW} requires the handler to both take and return the
 * type being constructed, so it can only ever hand back another {@code GlBackend}, and
 * {@code WgpuBackend} is not one.
 *
 * <p>What is rewritable is the {@code GpuBackend} argument of {@code new Window(...)}. Its stack
 * type is the interface, so a handler of {@code (GpuBackend) GpuBackend} is exactly the expected
 * shape. Replacing it there is also strictly better than replacing the list element: {@code Window}
 * passes that same reference to {@code createGlfwWindow}, which calls {@code setWindowHints()}
 * before {@code glfwCreateWindow} and stores it as its {@code backend} field. Substituting the
 * argument therefore covers all three uses at once -
 *
 * <ul>
 *   <li>{@link WgpuBackend#setWindowHints()} requests {@code GLFW_NO_API}, so no GL context is
 *       ever created and wgpu gets a plain window to attach a swapchain to;</li>
 *   <li>{@code handleWindowCreationErrors} is ours;</li>
 *   <li>{@code Window#backend()} returns the wgpu backend, so {@code Minecraft} calls
 *       {@link WgpuBackend#createDevice} rather than {@code GlBackend}'s.</li>
 * </ul>
 *
 * <p>The {@code GlBackend} that the array literal still constructs is left in place: its
 * constructor is empty, the instance is never stored anywhere, and removing it would mean
 * rewriting the array element instead, which is precisely the seam that cannot be typed.
 *
 * <p>The candidate list doubles as the fallback list, so a failing wgpu device no longer falls
 * back to OpenGL. That is deliberate: falling back would end up with both backends driving the
 * same window. The Rust side covers the "backend is unusable" case instead, by trying the other
 * wgpu backend before giving up.
 *
 * <p>{@code @ModifyArg} resolves {@code index} against
 * {@code Type.getArgumentTypes(descriptor)}, which excludes the receiver, so index 4 is the
 * {@code GpuBackend} parameter of
 * {@code Window(WindowEventHandler, DisplayData, String, String, GpuBackend)}. {@code require = 1}
 * makes a future change to that signature fail loudly instead of leaving the game rendering
 * through OpenGL with the mod silently doing nothing.
 */
@Mixin(Minecraft.class)
public class WgpuBackendSelectionMixin {

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/Window;<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;Ljava/lang/String;Lcom/mojang/blaze3d/systems/GpuBackend;)V"
            ),
            index = 4,
            require = 1
    )
    private static GpuBackend wgpu_mc$useWgpuBackend(GpuBackend original) {
        return new WgpuBackend();
    }
}
