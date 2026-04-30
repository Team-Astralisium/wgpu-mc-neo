package dev.birb.wgpu.mixin.render;

import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection")
public class BuiltChunkMixin {
    @Shadow
    @Final
    BlockPos.MutableBlockPos origin;

    // TODO: remap rebuild scheduling hook from Fabric ChunkBuilder.BuiltChunk.
}
