package dev.tabby.tab;

import net.minecraft.network.Connection;

/**
 * Exposes the owning Tabby context and transport for a client network listener.
 */
public interface TabOwnedListener {
    int tabby$getTabId();

    void tabby$setTabId(int tabId);

    Connection tabby$getConnection();
}
