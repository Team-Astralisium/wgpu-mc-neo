package dev.birb.wgpu.mixin.entity;

import dev.birb.wgpu.entity.EntityState;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRendererProvider.Context.class)
public class EntityRendererFactoryMixin {

    @Inject(method = "bakeLayer", at = @At("HEAD"))
    private void bakeLayer(ModelLayerLocation layer, CallbackInfoReturnable<ModelPart> cir) {
        if (EntityState.registeringRoot) {
            EntityState.EntityModelInfo info = new EntityState.EntityModelInfo();
            info.root = layer;
            EntityState.layers.put(EntityState.builderType, info);
            EntityState.registeringRoot = false;
        } else {
            EntityState.EntityModelInfo info = EntityState.layers.get(EntityState.builderType);
            if (info != null) {
                info.features.add(layer);
            }
        }
    }

}