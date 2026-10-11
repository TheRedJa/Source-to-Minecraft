package dev.theredja.src2mc.mixin;

import dev.theredja.src2mc.client.menu.MenuClient;
import net.minecraft.client.Camera;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the camera where an enabled {@code point_viewcontrol} has it (the in-world menu): once the camera has been set
 * up on the player as usual, its position and angles are replaced. Everything later in the frame (field of view,
 * culling, the map's look) reads the replaced camera.
 */
@Mixin(Camera.class)
public abstract class CameraMenuMixin {
    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Shadow protected abstract void setRotation(float yRot, float xRot, float roll);

    @Inject(method = "setup", at = @At("TAIL"))
    private void src2mc$viewcontrol(BlockGetter level, Entity entity, boolean detached, boolean thirdPersonReverse, float partialTick, CallbackInfo info) {
        double[] pose = MenuClient.cameraPose(partialTick);
        if (pose == null) return;
        setPosition(pose[0], pose[1], pose[2]);
        setRotation((float) pose[3], (float) pose[4], (float) pose[5]);
    }
}
