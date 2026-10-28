package dev.matvey.tabby.tab;

/**
 * Exposes the owning Tabby context for a client network listener.
 */
public interface TabOwnedListener {
    int tabby$getTabId();

    void tabby$setTabId(int tabId);
}
