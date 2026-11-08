package dev.tabby.tab;

import dev.tabby.mixin.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns tab creation, ordering, teardown, and single-player tab lifecycle rules.
 */
final class TabLifecycleManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");

    private TabLifecycleManager() {
    }

    static void createTab() {
        Minecraft client = TabManager.clientInstance();
        if (client == null) {
            return;
        }
        if (!TabRegistry.isEmpty() && TabRegistry.size() - 1 >= TabbyConfig.maxBackgroundTabs()) {
            SystemToast.add(
                client.getToasts(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("tabby.toast.limit_title"),
                Component.translatable("tabby.toast.limit_message")
            );
            LOGGER.debug("Tab creation rejected: background-tab limit {} reached", TabbyConfig.maxBackgroundTabs());
            return;
        }

        TabContext current = TabRegistry.get(TabRegistry.activeId());
        if (current != null) {
            current.captureFrom(client);
        }

        TabContext context = TabContext.createEmpty(client, TabRegistry.nextId());
        TabRegistry.put(context.id(), context);
        TabManager.switchTo(context.id());
    }

    static void togglePinned(int tabId) {
        Minecraft client = TabManager.clientInstance();
        TabContext context = TabRegistry.get(tabId);
        if (context == null) {
            return;
        }

        context.pinned = !context.pinned;
        moveTab(tabId, context.pinned ? 0 : TabRegistry.size());
        LOGGER.debug("Tab {} pin state changed to {}", tabId, context.pinned);
    }

    static void moveTab(int tabId, int targetIndex) {
        Minecraft client = TabManager.clientInstance();
        if (!TabRegistry.contains(tabId) || TabRegistry.size() < 2) {
            return;
        }

        List<TabContext> ordered = new ArrayList<>(TabRegistry.values());
        int oldIndex = -1;
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).id() == tabId) {
                oldIndex = index;
                break;
            }
        }
        if (oldIndex < 0) {
            return;
        }

        TabContext moving = ordered.remove(oldIndex);
        if (oldIndex < targetIndex) {
            targetIndex--;
        }

        int pinnedCount = (int) ordered.stream().filter(context -> context.pinned).count();
        if (moving.pinned) {
            targetIndex = Math.max(0, Math.min(pinnedCount, targetIndex));
        } else {
            targetIndex = Math.max(pinnedCount, Math.min(ordered.size(), targetIndex));
        }
        if (ordered.size() > 0 && targetIndex > ordered.size()) {
            targetIndex = ordered.size();
        }

        ordered.add(targetIndex, moving);
        TabRegistry.replaceOrder(ordered);
    }

    static void closeTab(int tabId) {
        Minecraft client = TabManager.clientInstance();
        if (client == null || !TabRegistry.contains(tabId)) {
            return;
        }

        if (tabId == TabRegistry.activeId() && TabRegistry.size() > 1) {
            List<Integer> ids = new ArrayList<>(TabRegistry.ids());
            int currentIndex = ids.indexOf(tabId);
            TabManager.switchTo(ids.get((currentIndex + 1) % ids.size()));
        }

        TabContext closing = TabRegistry.remove(tabId);
        if (closing == null) {
            return;
        }
        disposeTab(closing, true);

        if (TabRegistry.isEmpty()) {
            TabContext menu = TabContext.createEmpty(client, TabRegistry.nextId());
            TabRegistry.put(menu.id(), menu);
            TabRegistry.setActiveId(menu.id());
            menu.applyTo(client, false);
            client.setScreen(menu.screen);
            menu.captureFrom(client);
            LOGGER.debug("Closed final tab; returned to main menu context {}", menu.id());
        } else if (TabRegistry.activeId() == tabId) {
            TabManager.switchTo(TabRegistry.firstId());
        }
    }

    static void disposeTab(TabContext context, boolean closeRenderer) {
        Minecraft client = TabManager.clientInstance();
        int id = context.id();
        TabMemoryUsage.remove(id);
        TabDamageNotificationManager.removeTab(id);
        TabUnreadMessageManager.removeTab(id);
        TabReconnectManager.removeTab(id);
        TabCrashGuard.removeTab(id);

        ConnectionOwnershipRegistry.removeTab(id);

        stopIntegratedServerForTab(context);
        TabRendererCompatibility.suspendVoxyRenderer(context.levelRenderer, id);

        Connection pending = context.pendingConnection;
        Connection connection = context.networkConnection;
        if (pending != null && pending != connection) {
            pending.disconnect(Component.literal("Tab closed"));
        }
        if (connection != null) {
            connection.disconnect(Component.literal("Tab closed"));
        }
        if (context.level != null) {
            context.level.disconnect();
        }
        if (closeRenderer && context.levelRenderer != null) {
            context.levelRenderer.close();
        }
    }

    static boolean isSingleplayerTab(TabContext context) {
        Minecraft client = TabManager.clientInstance();
        if (context == null) {
            return false;
        }
        if (context.localServer) {
            return true;
        }

        Connection connection = context.networkConnection;
        if (connection == null && context.clientConnection != null) {
            connection = context.clientConnection.getConnection();
        }
        if (connection == null) {
            connection = context.pendingConnection;
        }
        return connection != null && connection.isMemoryConnection();
    }

    static boolean hasSingleplayerState(TabContext context) {
        Minecraft client = TabManager.clientInstance();
        return context != null && (
            context.level != null
                || context.player != null
                || context.connecting
                || context.pendingConnection != null
                    && (context.pendingConnection.isConnected() || context.pendingConnection.isConnecting())
                || context.networkConnection != null
                    && (context.networkConnection.isConnected() || context.networkConnection.isConnecting())
        );
    }

    static void stopIntegratedServerForTab(TabContext context) {
        Minecraft client = TabManager.clientInstance();
        if (client == null || !isSingleplayerTab(context)) {
            return;
        }

        boolean anotherTabOwnsLocalWorld = TabRegistry.values().stream()
            .anyMatch(other -> other.id() != context.id()
                && isSingleplayerTab(other)
                && hasSingleplayerState(other));
        if (anotherTabOwnsLocalWorld) {
            LOGGER.debug("Keeping integrated server alive while another single-player tab remains open");
            return;
        }

        MinecraftAccessor accessor = (MinecraftAccessor) client;
        IntegratedServer server = accessor.tabby$getSingleplayerServer();
        if (server != null) {
            accessor.tabby$setSingleplayerServer(null);
            // Do not let Minecraft.close() shut down the shared IO/chunk executors
            // while the integrated server is still saving chunks on its own thread.
            // halt(true) joins the server thread after scheduling the player-removal task.
            server.halt(true);
            if (!server.isShutdown()) {
                throw new IllegalStateException(
                    "Integrated server for tab " + context.id() + " did not fully stop"
                );
            }
            LOGGER.debug("Stopped integrated server owned by tab {} and joined its server thread", context.id());
        }
        accessor.tabby$setLocalServer(false);
        context.localServer = false;
    }

    static boolean hasInactiveSingleplayerWorld() {
        Minecraft client = TabManager.clientInstance();
        if (client == null) {
            return false;
        }
        return TabRegistry.values().stream().anyMatch(context ->
            context.id() != TabRegistry.activeId()
                && isSingleplayerTab(context)
                && hasSingleplayerState(context)
        );
    }

    static boolean shouldBlockSingleplayerWorldStart() {
        Minecraft client = TabManager.clientInstance();
        return hasInactiveSingleplayerWorld();
    }

    static boolean shouldPreserveIntegratedServerDuringDisconnect() {
        Minecraft client = TabManager.clientInstance();
        if (client == null
            || ((MinecraftAccessor) client).tabby$getSingleplayerServer() == null
            || !hasInactiveSingleplayerWorld()) {
            return false;
        }

        TabContext active = TabRegistry.get(TabRegistry.activeId());
        boolean activeOwnsMemoryConnection = isSingleplayerTab(active);
        boolean preserve = active != null && !activeOwnsMemoryConnection;
        LOGGER.debug(
            "Disconnect preservation check: activeTab={}, activeLocal={}, inactiveLocalWorld={}, integratedServerPresent={}, preserve={}",
            active == null ? -1 : active.id(),
            activeOwnsMemoryConnection,
            hasInactiveSingleplayerWorld(),
            ((MinecraftAccessor) client).tabby$getSingleplayerServer() != null,
            preserve
        );
        return preserve;
    }
}
