package dev.tabby.mixin.optional;

import dev.tabby.tab.TabManager;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Baritone has one process-global event bus. Its process ticks are scoped to the
 * original main tab, while world/player events from other tabs are rejected.
 */
@Mixin(targets = "baritone.event.GameEventHandler", remap = false)
public abstract class BaritoneGameEventHandlerMixin {
    @WrapMethod(method = "onTick")
    private void tabby$tickInMainTab(@Coerce Object event, Operation<Void> original) {
        TabManager.runBaritoneTickInMain(() -> original.call(event));
    }

    @WrapMethod(method = "onPostTick")
    private void tabby$postTickInMainTab(@Coerce Object event, Operation<Void> original) {
        TabManager.runBaritoneTickInMain(() -> original.call(event));
    }

    @Inject(
        method = {
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
