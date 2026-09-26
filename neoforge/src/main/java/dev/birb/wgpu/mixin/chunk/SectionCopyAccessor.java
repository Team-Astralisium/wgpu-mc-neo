package dev.birb.wgpu.mixin.chunk;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The section a rebuild snapshot holds, which is what the Rust baker reads.
 *
 * <p>{@code SectionCopy} is package-private, so the type cannot be named here - but the field's own
 * type, {@code PalettedContainer<BlockState>}, can, and that is all an accessor needs. The array the
 * copies live in is read as {@code Object[]} by {@link RenderSectionRegionAccessor} and each element
 * is cast to this interface, which the transformed class implements.
 *
 * <p>Reading the <em>copy</em> rather than the live chunk section is what makes the bake consistent
 * with the mesh Minecraft is building in the same task, and what makes it safe: a live
 * {@code LevelChunkSection} is guarded by a threading detector, and this runs on a chunk-build worker.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionCopy")
public interface SectionCopyAccessor {

    /** The section's block states, or {@code null} for a section that is all air or not loaded. */
    @Accessor("section")
    PalettedContainer<BlockState> wgpu_mc$states();
}