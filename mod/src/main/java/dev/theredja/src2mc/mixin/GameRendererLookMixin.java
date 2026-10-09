package dev.theredja.src2mc.mixin;

import dev.theredja.src2mc.client.look.LookClient;
import dev.theredja.src2mc.client.look.SourcePost;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Source's view of the camera's map (D30): what the frame takes from the map once the camera is
 * set up, and the engine's post-processing once the world and hand are drawn, before the GUI, as
 * {@code CViewRender::RenderView} runs {@code DoEnginePostProcessing} after the view models.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererLookMixin {
    @Shadow @Final private Camera mainCamera;

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
        shift = At.Shift.AFTER))
    private void src2mc$lookFrame(DeltaTracker deltaTracker, CallbackInfo info) {
        LookClient.update(mainCamera);
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
        shift = At.Shift.AFTER))
    private void src2mc$sourcePost(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo info) {
        SourcePost.afterLevel();
    }
}
