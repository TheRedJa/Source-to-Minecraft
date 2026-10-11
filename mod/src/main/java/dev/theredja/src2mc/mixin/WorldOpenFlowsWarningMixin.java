package dev.theredja.src2mc.mixin;

import dev.theredja.src2mc.client.levels.LevelChangeClient;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the "This world is using experimental settings" backup prompt for the worlds the level changes
 * open (a global dimension_type datapack makes every world's generation lifecycle experimental). Only while
 * {@link LevelChangeClient#switching()}; vanilla's world list keeps its warning. The old-customized-world
 * prompt (the {@code customized} flag) is left alone. Continuing runs the same callback the Proceed button does.
 */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsWarningMixin {
    @Inject(method = "askForBackup", at = @At("HEAD"), cancellable = true)
    private void src2mc$skipExperimentalWarning(LevelStorageSource.LevelStorageAccess access, boolean customized,
                                                Runnable proceed, Runnable cancel, CallbackInfo info) {
        if (customized || !LevelChangeClient.switching()) return;
        proceed.run();
        info.cancel();
    }
}
