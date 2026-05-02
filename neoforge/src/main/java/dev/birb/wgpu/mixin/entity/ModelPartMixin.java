package dev.birb.wgpu.mixin.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.birb.wgpu.entity.EntityState;
import dev.birb.wgpu.entity.ModelPartAccessor;
import dev.birb.wgpu.entity.ModelPartNameAccessor;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Map;

@Mixin(ModelPart.class)
public abstract class ModelPartMixin implements ModelPartNameAccessor, ModelPartAccessor {

    @Shadow public boolean visible;
    @Shadow public boolean skipDraw;

    @Shadow @Final private List<ModelPart.Cube> cubes;
    @Shadow @Final public Map<String, ModelPart> children;

    @Shadow public abstract void translateAndRotate(PoseStack matrices);
    @Shadow private void compile(PoseStack.Pose pose, VertexConsumer vertices, int light, int overlay, int color) {}

    private String name;
    private int partIndex;

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @author wgpu-mc
     * @reason Render entities in Rust
     */
    @Overwrite
    public void render(PoseStack matrices, VertexConsumer vertices, int light, int overlay, int color) {
        if (this.visible) {
            if (!this.cubes.isEmpty() || !this.children.isEmpty()) {
                matrices.pushPose();
                this.translateAndRotate(matrices);
                if (!this.skipDraw) {
                    this.compile(matrices.last(), vertices, light, overlay, color);
                }

                Matrix4f mat4 = matrices.last().pose();
                String thisPartName = ((ModelPartNameAccessor) (Object) this).getName();
                if (thisPartName == null) {
                    thisPartName = "root";
                }

                EntityState.ModelPartState state = new EntityState.ModelPartState();
                state.overlay = color;
                state.mat = mat4;
                EntityState.entityModelPartStates.put(thisPartName, state);

                for (ModelPart modelPart : this.children.values()) {
                    modelPart.render(matrices, vertices, light, overlay, color);
                }

                matrices.popPose();
            }
        }
    }

    @Override
    public void setModelPartIndex(int partIndex) {
        this.partIndex = partIndex;
    }

}
