package dev.birb.wgpu.mixin.render;

import net.minecraft.client.renderer.Lightmap;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 26.1 renamed {@code LightTexture} to {@code Lightmap} and dropped its
 * {@code (GameRenderer, Minecraft)} constructor in favour of a no-arg one, so the
 * legacy constructor hook no longer has a target. The mixin is retained as an
 * anchor for the upcoming lightmap upload path.
 */
@Mixin(Lightmap.class)
public class LightmapTextureManagerMixin {
}