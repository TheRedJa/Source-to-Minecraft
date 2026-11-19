package dev.theredja.src2mc.mixin;

import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import dev.theredja.src2mc.logic.MoverSystem;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sable sends a sub-level to the players within {@code sub_level_tracking_range} of its centre, checked every tick
 * ({@code SubLevelTrackingSystem.shouldLoad}, Sable 2.0.3). A player inside a placed map also gets every sub-level
 * inside that map, as Source networks the map's movers: otherwise a train far away arrives on the client only after
 * {@code point_teleport} has put the player where it stands, and the player is left behind on the tracks.
 */
@Mixin(value = SubLevelTrackingSystem.class, remap = false)
public abstract class SableTrackingMixin {
    @Inject(method = "shouldLoad", at = @At("HEAD"), cancellable = true)
    private void src2mc$wholeMap(Player player, Vector3dc position, CallbackInfoReturnable<Boolean> info) {
        if (MoverSystem.sameMap(player, position.x(), position.y(), position.z())) info.setReturnValue(true);
    }
}
