package dev.birb.wgpu.mixin.render;

import dev.birb.wgpu.render.RebuildTaskAccessor;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public class RebuildTaskMixin implements RebuildTaskAccessor {

    private Object builtChunk;

    @Override
    public void wgpu_mc$setBuiltChunk(Object builtChunk) {
        this.builtChunk = builtChunk;
    }
}