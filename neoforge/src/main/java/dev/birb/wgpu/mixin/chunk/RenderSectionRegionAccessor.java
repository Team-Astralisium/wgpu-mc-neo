package dev.birb.wgpu.mixin.chunk;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The sections a rebuild snapshot covers.
 *
 * <p>26.1 hands the compile task a {@code RenderSectionRegion}: the 3x3x3 sections around the one
 * being rebuilt, copied out of the level so the task can read them off-thread. The middle entry is
 * the section being rebuilt, and its position is what the Rust baker is keyed by.
 *
 * <p>The section copies themselves are reached through {@code WgpuSectionCopies}, which lives inside
 * Minecraft's package because a plain accessor cannot name their package-private type.
 */
@Mixin(RenderSectionRegion.class)
public interface RenderSectionRegionAccessor {

    @Accessor("minSectionX")
    int wgpu_mc$minSectionX();

    @Accessor("minSectionY")
    int wgpu_mc$minSectionY();

    @Accessor("minSectionZ")
    int wgpu_mc$minSectionZ();


}
