package dev.tabby.mixin;

import dev.tabby.tab.TabManager;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rejects a second single-player load before vanilla changes the active screen
 * or begins preparing the new world.
 */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
    @Inject(
        method = "openWorld(Ljava/lang/String;Ljava/lang/Runnable;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void tabby$blockSecondSingleplayerWorld(String levelId, Runnable onWorldLoaded, CallbackInfo ci) {
        if (TabManager.shouldBlockSingleplayerWorldStart()) {
            TabManager.showSingleplayerWorldBlockedNotice();
            ci.cancel();
        }
    }
}
