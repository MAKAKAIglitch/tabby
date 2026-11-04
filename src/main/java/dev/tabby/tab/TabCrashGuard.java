package dev.tabby.tab;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contains recoverable runtime failures raised while a hidden tab processes packets.
 * JVM Errors and exceptions from the active tab deliberately retain vanilla crash behaviour.
 */
final class TabCrashGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    private static final Map<Integer, Throwable> PENDING = new LinkedHashMap<>();

    private TabCrashGuard() {
    }

    static boolean shouldIsolate(int activeTabId, int failingTabId) {
        return failingTabId >= 0 && failingTabId != activeTabId;
    }

    static void queueBackgroundFailure(int tabId, Throwable exception) {
        if (exception == null || !shouldIsolate(TabRegistry.activeId(), tabId)) {
            return;
        }
        TabContext context = TabRegistry.get(tabId);
        if (context == null || PENDING.containsKey(tabId)) {
            return;
        }

        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = exception.getClass().getSimpleName();
        }
        context.lastBackgroundFailure = detail.length() > 120
            ? detail.substring(0, 117) + "..."
            : detail;
        PENDING.put(tabId, exception);
        LOGGER.error(
            "Tabby isolated a runtime/linkage failure in background tab {} ({}); the full stack trace follows",
            tabId,
            context.displayTitle(),
            exception
        );
    }

    static void tick(Minecraft client) {
        if (client == null || PENDING.isEmpty()) {
            return;
        }

        for (Map.Entry<Integer, Throwable> entry : Map.copyOf(PENDING).entrySet()) {
            int tabId = entry.getKey();
            PENDING.remove(tabId);
            TabContext context = TabRegistry.get(tabId);
            if (context == null || context.disconnecting) {
                continue;
            }

            String title = context.displayTitle();
            TabReconnectManager.cancel(tabId);
            TabConnectionManager.disconnectTabToMenu(
                tabId,
                Component.literal("Tabby isolated a background-tab runtime failure"),
                true
            );
            SystemToast.add(
                client.getToastManager(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("Tabby"),
                Component.translatable("tabby.toast.background_crash", title)
            );
            LOGGER.warn("Background tab {} was safely disconnected after a packet-handler failure", tabId);
        }
    }

    static boolean hasFailure(int tabId) {
        TabContext context = TabRegistry.get(tabId);
        return context != null && context.lastBackgroundFailure != null;
    }

    static boolean hasPendingFailure(int tabId) {
        return PENDING.containsKey(tabId);
    }

    static int pendingCount() {
        return PENDING.size();
    }

    static void removeTab(int tabId) {
        PENDING.remove(tabId);
    }

    static void clear() {
        PENDING.clear();
    }
}
