package dev.matvey.tabby.mixin;

import dev.matvey.tabby.tab.TabOwnedListener;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
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
}
