package dev.tabby.mixin.optional;

import dev.tabby.tab.TabManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Baritone has one process-global event bus. Reject callbacks originating from
 * any Tabby context other than the original main tab.
 */
@Mixin(targets = "baritone.event.GameEventHandler", remap = false)
public abstract class BaritoneGameEventHandlerMixin {
    @Inject(
        method = {
            "onTick",
            "onPostTick",
            "onPlayerUpdate",
            "onSendChatMessage",
            "onPreTabComplete",
            "onChunkEvent",
            "onBlockChange",
            "onRenderPass",
            "onWorldEvent",
            "onSendPacket",
            "onReceivePacket",
            "onPlayerRotationMove",
            "onPlayerSprintState",
            "onBlockInteract",
            "onPlayerDeath",
            "onPathEvent"
        },
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void tabby$restrictEventsToMainTab(CallbackInfo ci) {
        if (!TabManager.isBaritoneContextAllowed()) {
            ci.cancel();
        }
    }
}
