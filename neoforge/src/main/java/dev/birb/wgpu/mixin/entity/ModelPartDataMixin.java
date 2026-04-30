package dev.birb.wgpu.mixin.entity;

import com.google.common.collect.ImmutableList;
import dev.birb.wgpu.entity.ModelPartNameAccessor;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Mixin(PartDefinition.class)
public class ModelPartDataMixin {
    @Shadow
    @Final
    private Map<String, PartDefinition> children;

    @Shadow
    @Final
    private List<CubeDefinition> cubes;

    @Shadow
    @Final
    private PartPose partPose;

    /**
     * @author wgpu-mc
     * @reason Intercept baked model-part naming for Rust-side tracking.
     */
    @Overwrite
    public ModelPart bake(int textureWidth, int textureHeight) {
        Object2ObjectArrayMap<String, ModelPart> object2objectarraymap = this.children.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                (entry) -> {
                    ModelPart part = entry.getValue().bake(textureWidth, textureHeight);
                    ((ModelPartNameAccessor) (Object) part).setName(entry.getKey());
                    return part;
                },
                (modelPartA, modelPartB) -> modelPartA,
                Object2ObjectArrayMap::new
        ));
        List<ModelPart.Cube> cubesList = this.cubes.stream().map((cubeDefinition) -> cubeDefinition.bake(textureWidth, textureHeight)).collect(ImmutableList.toImmutableList());
        ModelPart modelPart = new ModelPart(cubesList, object2objectarraymap);
        modelPart.setInitialPose(this.partPose);
        modelPart.loadPose(this.partPose);
        return modelPart;
    }
}
