package dev.tabby.mixin.optional;

import dev.tabby.compat.SimpleVoiceChatManagerAccess;
import dev.tabby.tab.TabManager;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional integration with Simple Voice Chat. The target class is absent when SVC
 * is not installed, so this mixin must never become a required runtime dependency.
 */
@Pseudo
@Mixin(
    targets = "de.maxhenkel.voicechat.voice.client.ClientManager",
    remap = false
)
public abstract class SimpleVoiceChatClientManagerMixin implements SimpleVoiceChatManagerAccess {
    @Override
    @Invoker(value = "onJoinWorld", remap = false)
    public abstract void tabby$invokeOnJoinWorld();

    @Override
    @Invoker(value = "onDisconnect", remap = false)
    public abstract void tabby$invokeOnDisconnect();

    @Inject(
        method = "onJoinWorld()V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void tabby$ignoreInactiveTabJoin(CallbackInfo ci) {
        if (TabManager.isBackgroundContext()) {
            ci.cancel();
        }
    }

    @Inject(
        method = "onDisconnect()V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void tabby$ignoreInactiveTabDisconnect(CallbackInfo ci) {
        if (TabManager.isBackgroundContext()) {
            ci.cancel();
        }
    }
}
