package dev.birb.wgpu.mixin.render;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin {

    /**
     * @author wgpu-mc
     * @reason do no such thing
     */
    @Overwrite
    public void onResourceManagerReload(ResourceManager manager) {
    }

}