package dev.birb.wgpu.mixin.render;

import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import dev.birb.wgpu.render.RebuildTaskAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection")
public class BuiltChunkMixin {
    @Shadow
    @Final
    BlockPos.MutableBlockPos origin;

    @Inject(method = "createCompileTask", at = @At("RETURN"))
    private void wgpu_mc$wireCompileTaskOwner(
            RenderRegionCache regionCache,
            CallbackInfoReturnable<?> cir
    ) {
        Object task = cir.getReturnValue();
        if (task instanceof RebuildTaskAccessor accessor) {
            accessor.wgpu_mc$setBuiltChunk(this);
        }
    }
}
