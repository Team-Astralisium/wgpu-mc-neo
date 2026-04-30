package dev.birb.wgpu.mixin.entity;

import com.google.common.collect.ImmutableMap;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.birb.wgpu.entity.EntityState;

@Mixin(EntityRenderers.class)
public class EntityRenderersMixin {

    @Inject(
            method = "lambda$createEntityRenderers$0",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/ImmutableMap$Builder;put(Ljava/lang/Object;Ljava/lang/Object;)Lcom/google/common/collect/ImmutableMap$Builder;",
                    shift = At.Shift.BEFORE
            )
    )
    @Dynamic
    private static void wgpu_mc$reloadEntityRenderers(
            ImmutableMap.Builder<EntityType<?>, ?> builder,
            EntityRendererProvider.Context context,
            EntityType<?> entityType,
            EntityRendererProvider<?> factory,
            CallbackInfo ci
    ) {
        EntityState.builderType = entityType;
        EntityState.registeringRoot = true;
    }
}
