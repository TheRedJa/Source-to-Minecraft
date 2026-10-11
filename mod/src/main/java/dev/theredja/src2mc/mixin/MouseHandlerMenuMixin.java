package dev.theredja.src2mc.mixin;

import dev.theredja.src2mc.client.menu.MenuClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The player held by a {@code point_viewcontrol} does not look around with the mouse (Source's FL_FROZEN). */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMenuMixin {
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void src2mc$frozen(double movementTime, CallbackInfo info) {
        if (MenuClient.viewActive()) info.cancel();
    }
}
