package dev.theredja.src2mc.mixin;

import dev.theredja.src2mc.client.render.EntityLighting;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An entity in a placed map takes its light from Source's ambient cube; see {@link EntityLighting}. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererLightMixin {
    @Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
    private void src2mc$sourceLight(Entity entity, float partialTicks, CallbackInfoReturnable<Integer> info) {
        int vanilla = info.getReturnValueI();
        int light = EntityLighting.packedLight(entity, partialTicks, vanilla);
        if (light != vanilla) info.setReturnValue(light);
    }
}
