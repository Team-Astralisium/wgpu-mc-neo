package dev.birb.wgpu.mixin.chunk;

import dev.birb.wgpu.chunk.RustChunkBake;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds the Rust terrain baker from the section rebuild that Minecraft is already running.
 *
 * <p>26.1 rebuilds a section in
 * {@code SectionRenderDispatcher$RenderSection$RebuildTask#doTask(SectionBufferBuilderPack)}, on the
 * chunk-build worker thread, from the {@code RenderSectionRegion} snapshot the task was created
 * with. Hooking its head means the Rust bake reads the same snapshot, on the same thread, for the
 * same section - no second copy of the world and no extra scheduling.
 *
 * <p>Nothing is cancelled and nothing is replaced yet: the task still builds Minecraft's own mesh,
 * which is what still draws the world. The Rust side bakes into its arena in parallel and the switch
 * in {@link RustChunkBake} decides whether it does at all, so this hook costs a boolean check when
 * it is off.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$RebuildTask")
public class RustChunkBakeMixin {

    @Shadow
    @Final
    protected RenderSectionRegion region;

    @Inject(method = "doTask", at = @At("HEAD"))
    private void wgpuMc$bakeInRust(SectionBufferBuilderPack pack, CallbackInfoReturnable<Object> cir) {
        RustChunkBake.bake(this.region);
    }
}