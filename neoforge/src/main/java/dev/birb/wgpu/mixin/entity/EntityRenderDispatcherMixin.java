package dev.birb.wgpu.mixin.entity;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Shadow public abstract <T extends Entity> EntityRenderer<? super T> getRenderer(T entity);

    @Shadow private Map<EntityType<?>, EntityRenderer<?>> renderers;

    private static int getOverlayColor(int packedUV) {
        int u = packedUV & 0xffff;
        int v = packedUV >> 16;

        if (v < 8) {
            return -1308622593;
        }

        return (((int) ((1.0f - (float) u / 15.0f * 0.75f) * 255.0f)) << 24) | 0xFFFFFF;
    }

}