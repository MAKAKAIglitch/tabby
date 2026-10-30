package dev.tabby.mixin;

import dev.tabby.tab.TabOwnedListener;
import dev.tabby.tab.TabManager;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientPacketListenerMixin implements TabOwnedListener {
    @Unique
    private volatile int tabby$tabId = -1;

    @Override
    public int tabby$getTabId() {
        return this.tabby$tabId;
    }

    @Override
    public void tabby$setTabId(int tabId) {
        this.tabby$tabId = tabId;
    }

    @Inject(
        method = "onDisconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void tabby$scopeDisconnectToOwningTab(DisconnectionDetails details, CallbackInfo ci) {
        if (TabManager.handleListenerDisconnect(
            (ClientCommonPacketListenerImpl) (Object) this,
            details
        )) {
            ci.cancel();
        }
    }
}
