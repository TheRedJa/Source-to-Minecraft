package dev.theredja.src2mc.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.theredja.src2mc.client.render.EntityLighting;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** A living entity in a placed map wears the colour of Source's ambient light; see {@link EntityLighting}. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererTintMixin {
    @ModifyArg(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
        index = 4)
    private int src2mc$sourceTint(int color, @Local(argsOnly = true) LivingEntity entity, @Local(argsOnly = true, ordinal = 1) float partialTicks) {
        return EntityLighting.tint(entity, partialTicks, color);
    }
}
