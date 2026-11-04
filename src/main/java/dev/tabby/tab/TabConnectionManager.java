package dev.tabby.tab;

import dev.tabby.compat.SimpleVoiceChatCompatibility;

import dev.tabby.mixin.MinecraftAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Owns connection-attempt policy, per-tab disconnect flows, and network callbacks.
 * Connection identity is resolved by ConnectionOwnershipRegistry; tab lifecycle
 * and integrated-server teardown are delegated to TabLifecycleManager.
 */
final class TabConnectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");

    private TabConnectionManager() {
    }

    /**
     * Movement packets belong exclusively to PLAY. Reconfiguration can temporarily
     * leave a LocalPlayer alive while the same channel has already switched to
     * CONFIGURATION (for example during proxy/Limbo transitions). Such packets are
     * invalid for the active encoder and must not be allowed to reach Netty.
     */
    static boolean shouldSuppressOutOfPhaseMovement(Packet<?> packet, ConnectionProtocol phase) {
        boolean movementPacket = packet instanceof ServerboundMovePlayerPacket
            || packet instanceof ServerboundMoveVehiclePacket;
        return movementPacket && phase != ConnectionProtocol.PLAY;
    }


    /**
     * Handles the PauseScreen's Disconnect button without invoking Minecraft's
     * global disconnect routine when more than one tab is open.
     */
    static boolean disconnectActiveTabFromPauseScreen() {
        Minecraft client = TabManager.clientInstance();
        if (client == null || TabRegistry.size() <= 1) {
            return false;
        }

        TabContext context = TabRegistry.get(TabRegistry.activeId());
        if (context == null || !hasTabConnection(context)) {
            return false;
        }

        LOGGER.info("Tabby: Disconnect pressed for active tab {} while {} tabs are open", TabRegistry.activeId(), TabRegistry.size());
        return disconnectTabToMenu(context.id(), Component.literal("Disconnected by user"), true);
    }

    /**
     * Handles a connection listener's disconnect callback in the context that owns it.
     * A background server must never call Minecraft.disconnect() against the visible tab.
     */
    static boolean handleListenerDisconnect(
        ClientCommonPacketListenerImpl listener,
        DisconnectionDetails details
    ) {
        Minecraft client = TabManager.clientInstance();
        if (client == null || listener == null) {
            return false;
        }

        int ownerId = ConnectionOwnershipRegistry.ownerIdFor(listener);
        if (ownerId < 0) {
            LOGGER.warn("Could not scope disconnect callback for unowned listener {}", listener.getClass().getSimpleName());
            return false;
        }

        TabContext context = TabRegistry.get(ownerId);
        if (context == null) {
            // The tab can already have been removed by closeTab(). Its late network
            // callback still must not run vanilla's singleton-wide disconnect.
            return ownerId != TabRegistry.activeId() || TabRegistry.isEmpty();
        }

        if (context.disconnecting) {
            return true;
        }

        Connection listenerConnection = ((TabOwnedListener) listener).tabby$getConnection();
        boolean ownsListener = context.clientConnection == listener
            || context.networkConnection == listenerConnection
            || context.pendingConnection == listenerConnection
            || context.replacingConnection == listenerConnection;
        if (!ownsListener) {
            // A mapped listener can finish disconnecting after its tab snapshot was
            // cleared by an earlier Connection.handleDisconnection callback. It still
            // belongs to this inactive tab and must never reach Minecraft.disconnect().
            LOGGER.debug("Cancelled stale disconnect callback for tab {} while {} tabs remain", ownerId, TabRegistry.size());
            return true;
        }

        boolean replacingOldConnection = context.connecting && context.replacingConnection == listenerConnection;
        if (!replacingOldConnection && !TabManager.isClientDisconnectInProgress()) {
            TabReconnectManager.onUnexpectedDisconnect(context, listenerConnection, details.reason());
        }
        if (TabRegistry.size() <= 1) {
            return false;
        }

        Minecraft currentClient = client;
        Runnable disconnect = () -> disconnectTabToMenu(
            ownerId,
            details.reason(),
            false
        );
        if (currentClient.isSameThread()) {
            if (TabManager.currentContextId() == ownerId) {
                disconnect.run();
            } else {
                TabManager.runIn(ownerId, disconnect);
                restoreVisibleSharedRenderState(ownerId);
            }
        } else {
            currentClient.execute(() -> {
                if (TabManager.currentContextId() == ownerId) {
                    disconnect.run();
                } else {
                    TabManager.runIn(ownerId, disconnect);
                    restoreVisibleSharedRenderState(ownerId);
                }
            });
        }
        LOGGER.info("Tabby: scoped disconnect callback to tab {} instead of the global client", ownerId);
        return true;
    }

    static boolean hasTabConnection(TabContext context) {
        Minecraft client = TabManager.clientInstance();
        return context.level != null
            || context.player != null
            || context.clientConnection != null
            || context.networkConnection != null
            || context.pendingConnection != null
            || context.connecting
            || context.localServer;
    }

    static boolean disconnectTabToMenu(int tabId, Component reason, boolean closeConnection) {
        Minecraft client = TabManager.clientInstance();
        if (client == null) {
            return false;
        }

        TabContext context = TabRegistry.get(tabId);
        if (context == null) {
            return false;
        }
        if (context.disconnecting) {
            return true;
        }
        if (closeConnection) {
            TabReconnectManager.cancel(tabId);
        }

        Runnable operation = () -> disconnectInstalledTabToMenu(context, reason, closeConnection);
        if (TabManager.currentContextId() == tabId) {
            operation.run();
        } else {
            TabManager.runIn(tabId, operation);
            restoreVisibleSharedRenderState(tabId);
        }
        return true;
    }

    static void disconnectInstalledTabToMenu(
        TabContext context,
        Component reason,
        boolean closeConnection
    ) {
        Minecraft client = TabManager.clientInstance();
        if (client == null || context.disconnecting) {
            return;
        }

        Connection network = context.networkConnection;
        Connection pending = context.pendingConnection;
        ClientPacketListener listener = context.clientConnection;
        LevelRenderer renderer = context.levelRenderer;
        boolean background = context.id() != TabRegistry.activeId();

        context.disconnecting = true;
        context.disconnectingConnection = network;
        context.disconnectingPendingConnection = pending;

        if (context.localServer) {
            TabLifecycleManager.stopIntegratedServerForTab(context);
        }

        if (renderer != null) {
            renderer.setLevel(null);
        }

        context.level = null;
        context.player = null;
        context.gameMode = null;
        context.clientConnection = null;
        context.networkConnection = null;
        context.packetListener = null;
        context.pendingConnection = null;
        context.replacingConnection = null;
        context.playerName = null;
        context.connecting = false;
        context.localServer = false;
        context.worldTransitioning = false;
        context.worldTransitionTicks = 0;
        context.serverData = null;
        context.setTitle("Main Menu");
        context.gui = new Gui(client);
        context.hitResult = null;
        context.crosshairPickEntity = null;
        context.cameraEntity = null;
        context.screen = new TitleScreen();

        MinecraftAccessor accessor = (MinecraftAccessor) client;
        accessor.tabby$setPendingConnection(null);
        accessor.tabby$setLocalServer(false);
        client.level = null;
        client.player = null;
        client.gameMode = null;
        client.hitResult = null;
        client.crosshairPickEntity = null;
        client.cameraEntity = null;
        client.gui = context.gui;

        // The dispatcher and particle engine are shared by Minecraft. A background
        // disconnect temporarily installs that tab, so restore the visible tab's
        // shared render state after TabManager.runIn() returns.
        client.entityRenderDispatcher.setLevel(null);
        if (!background && client.particleEngine != null) {
            client.particleEngine.setLevel(null);
        }
        if (!background) {
            KeyMapping.releaseAll();
            client.getSoundManager().stop();
        }
        client.setScreen(context.screen);

        if (closeConnection) {
            if (listener != null) {
                listener.close();
            }
            if (network != null && (network.isConnected() || network.isConnecting())) {
                network.disconnect(reason);
            }
            if (pending != null && pending != network
                && (pending.isConnected() || pending.isConnecting())) {
                pending.disconnect(reason);
            }
        }

        if (context.disconnectingConnection == null && context.disconnectingPendingConnection == null) {
            context.disconnecting = false;
        }

        if (!background) {
            SimpleVoiceChatCompatibility.onActiveTabDisconnected();
        }

        LOGGER.info(
            "Tabby: disconnected only tab {} (connectionCloseRequested={}, otherTabs={})",
            context.id(),
            closeConnection,
            TabRegistry.size() - 1
        );
    }

    static void restoreVisibleSharedRenderState(int disconnectedTabId) {
        Minecraft client = TabManager.clientInstance();
        if (client == null || disconnectedTabId == TabRegistry.activeId()) {
            return;
        }

        TabContext visible = TabRegistry.get(TabRegistry.activeId());
        if (visible != null) {
            TabManager.restoreClientGlobals(visible);
        }
    }

    /**
     * Vanilla disconnects the global client before every server connection attempt.
     * That is correct only when the currently selected tab owns the world being
     * disconnected. A freshly-created empty tab must not tear down an integrated
     * server or client world retained by another tab.
     */
    static void disconnectCurrentTabBeforeConnecting(Minecraft minecraft) {
        Minecraft client = TabManager.clientInstance();
        if (client == null || minecraft != client) {
            minecraft.disconnect();
            return;
        }

        TabContext context = TabRegistry.get(TabManager.currentContextId());
        if (context == null) {
            minecraft.disconnect();
            return;
        }

        Connection pending = context.pendingConnection;
        Connection network = context.networkConnection;
        boolean livePending = pending != null && (pending.isConnected() || pending.isConnecting());
        boolean liveNetwork = network != null && (network.isConnected() || network.isConnecting());
        boolean ownsWorld = context.level != null
            || context.player != null
            || context.gameMode != null
            || context.localServer;
        boolean ownsLiveListener = context.clientConnection != null
            && context.clientConnection.getConnection().isConnected();

        if (ownsWorld || ownsLiveListener || liveNetwork || livePending) {
            LOGGER.debug(
                "Running vanilla disconnect for tab {} before replacing its own connection (world={}, localServer={}, liveConnection={})",
                context.id(),
                context.level != null,
                context.localServer,
                ownsLiveListener || liveNetwork || livePending
            );
            minecraft.disconnect();
            return;
        }

        // An empty tab has no client-side world state to disconnect. Do not run
        // Minecraft.disconnect(), because its singleton cleanup may clear the
        // retained world or integrated-server state of an inactive tab.
        Connection nativePending = ((MinecraftAccessor) minecraft).tabby$getPendingConnection();
        if (nativePending != null && nativePending == pending) {
            nativePending.disconnect(Component.literal("Tab connection replaced"));
            ((MinecraftAccessor) minecraft).tabby$setPendingConnection(null);
        }
        LOGGER.debug(
            "Skipped vanilla global disconnect for empty tab {} before opening a new server connection",
            context.id()
        );
    }

    static boolean handleConnectionAttempt(ServerAddress address, ServerData serverData) {
        Minecraft client = TabManager.clientInstance();
        if (client == null || address == null || serverData == null) {
            return false;
        }

        int targetId = TabManager.currentContextId();
        TabContext target = TabRegistry.get(targetId);
        if (target == null) {
            return false;
        }
        if (!TabReconnectManager.isAutomaticLaunch(targetId)) {
            TabReconnectManager.cancel(targetId);
        }

        String username = client.getUser().getName();
        for (TabContext existing : TabRegistry.values()) {
            if (existing.id() == targetId
                || !username.equals(existing.playerName)
                || !hasLiveConnectionAttempt(existing)
                || !sameServer(existing.serverData, address)) {
                continue;
            }

            String existingTitle = existing.title();
            if (existingTitle == null || existingTitle.isBlank()) {
                existingTitle = address.toString();
            }
            SystemToast.add(
                client.getToastManager(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("Tabby"),
                Component.literal("This server is already open in tab '" + existingTitle + "'.")
            );
            LOGGER.warn(
                "Blocked duplicate connection to {} for player {} (already open in tab {})",
                address,
                username,
                existing.id()
            );
            return true;
        }

        Connection oldConnection = target.networkConnection;
        if (oldConnection == null || (!oldConnection.isConnected() && !oldConnection.isConnecting())) {
            oldConnection = target.pendingConnection;
        }
        target.replacingConnection = oldConnection != null
            && (oldConnection.isConnected() || oldConnection.isConnecting())
                ? oldConnection
                : null;
        target.serverData = serverData;
        target.playerName = username;
        target.connecting = true;

        String title = serverData.name;
        if (title == null || title.isBlank()) {
            title = serverData.ip;
        }
        target.setTitle(title == null || title.isBlank() ? address.toString() : title);
        LOGGER.debug("Tab {} is connecting to {}", target.id(), address);
        return false;
    }

    static boolean sameServer(ServerData existingData, ServerAddress address) {
        Minecraft client = TabManager.clientInstance();
        if (existingData == null || existingData.ip == null || existingData.ip.isBlank()) {
            return false;
        }

        ServerAddress existingAddress = ServerAddress.parseString(existingData.ip);
        return existingAddress.getPort() == address.getPort()
            && existingAddress.getHost().equalsIgnoreCase(address.getHost());
    }

    static boolean hasLiveConnectionAttempt(TabContext context) {
        Minecraft client = TabManager.clientInstance();
        return context.connecting
            || context.networkConnection != null
                && (context.networkConnection.isConnected() || context.networkConnection.isConnecting())
            || context.pendingConnection != null
                && (context.pendingConnection.isConnected() || context.pendingConnection.isConnecting());
    }

    static int uniqueConnectingTabId() {
        Minecraft client = TabManager.clientInstance();
        int candidate = -1;
        for (TabContext context : TabRegistry.values()) {
            if (!context.connecting) {
                continue;
            }
            if (candidate >= 0) {
                return -1;
            }
            candidate = context.id();
        }
        return candidate;
    }

    static boolean onScreenChangeRequested(Screen screen) {
        Minecraft client = TabManager.clientInstance();
        if (client == null
            || screen == null
            || screen instanceof net.minecraft.client.gui.screens.ConnectScreen
            || screen instanceof net.minecraft.client.gui.screens.ProgressScreen) {
            // ConnectScreen.startConnecting calls Minecraft.disconnect(), which briefly installs
            // a ProgressScreen before it installs the new ConnectScreen. Keep the attempt marker
            // through that vanilla transition so connection ownership remains bound to this tab.
            return false;
        }

        TabContext context = TabRegistry.get(TabManager.currentContextId());
        if (context != null
            && screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen
            && TabReconnectManager.isInFlight(context.id())) {
            context.connecting = false;
            TabReconnectManager.onRetryFailed(context.id());
        }
        if (context == null || !context.connecting) {
            return false;
        }

        if (context.replacingConnection != null
            && screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen) {
            // The previous listener may publish a late disconnect screen while the new
            // ConnectScreen is active. Do not let the stale screen interrupt the new attempt.
            LOGGER.debug("Suppressed stale disconnect screen while tab {} replaces a connection", context.id());
            return true;
        }

        if (context.replacingConnection != null) {
            return false;
        }

        if (context.networkConnection == null || !context.networkConnection.isConnected()) {
            context.connecting = false;
            LOGGER.debug("Cleared failed or cancelled connection attempt for tab {}", context.id());
        }
        return false;
    }

    static void onConnectionDisconnected(Connection connection) {
        Minecraft client = TabManager.clientInstance();
        Minecraft currentClient = client;
        if (currentClient == null || connection == null) {
            return;
        }

        Runnable cleanup = () -> cleanupDisconnectedConnection(connection);
        if (currentClient.isSameThread()) {
            cleanup.run();
        } else {
            currentClient.execute(cleanup);
        }
    }

    static void cleanupDisconnectedConnection(Connection connection) {
        Minecraft client = TabManager.clientInstance();
        PacketListener disconnectedListener = connection.getPacketListener();
        LOGGER.debug(
            "Connection cleanup observed {} (listener={}, owner={})",
            System.identityHashCode(connection),
            disconnectedListener == null ? "null" : disconnectedListener.getClass().getSimpleName(),
            ConnectionOwnershipRegistry.ownerIdFor(disconnectedListener)
        );

        for (TabContext context : TabRegistry.values()) {
            boolean replacingOldConnection = context.connecting && context.replacingConnection == connection;
            boolean ownsConnection = context.networkConnection == connection
                || context.pendingConnection == connection
                || context.replacingConnection == connection
                || context.disconnectingConnection == connection
                || context.disconnectingPendingConnection == connection
                || context.clientConnection != null
                    && context.clientConnection.getConnection() == connection;
            if (!ownsConnection) {
                continue;
            }

            if (!replacingOldConnection
                && !context.disconnecting
                && !TabManager.isClientDisconnectInProgress()) {
                // Connection.handleDisconnection() runs before ClientCommonPacketListenerImpl.onDisconnect().
                // Start the retry while this context still identifies the Connection as its owner;
                // the later listener callback may arrive after these references are cleared.
                TabReconnectManager.onUnexpectedDisconnect(
                    context,
                    connection,
                    Component.literal("Connection closed")
                );
            }

            if (context.localServer && context.level != null && !context.worldTransitioning) {
                LOGGER.warn(
                    "Disconnect cleanup reached integrated-world tab {} while active tab {} owns the client (localServer={}, level={}, connectionMatch={}, listenerMatch={})",
                    context.id(),
                    TabRegistry.activeId(),
                    context.localServer,
                    System.identityHashCode(context.level),
                    context.networkConnection == connection,
                    context.clientConnection != null && context.clientConnection.getConnection() == connection
                );
            }

            if (context.networkConnection == connection) {
                context.networkConnection = null;
            }
            if (context.pendingConnection == connection) {
                context.pendingConnection = null;
            }
            if (context.clientConnection != null
                && context.clientConnection.getConnection() == connection) {
                context.clientConnection = null;
            }
            if (context.packetListener == disconnectedListener) {
                context.packetListener = null;
            }

            if (replacingOldConnection) {
                context.replacingConnection = null;
                if (connection.isMemoryConnection()) {
                    context.localServer = false;
                }
                context.level = null;
                context.player = null;
                context.gameMode = null;
                if (context.levelRenderer != null) {
                    context.levelRenderer.setLevel(null);
                }
                // Preserve the new attempt's serverData/title and its connecting marker.
                LOGGER.debug("Released replaced connection from tab {}", context.id());
                continue;
            }

            if (context.replacingConnection == connection) {
                context.replacingConnection = null;
            }
            if (context.disconnectingConnection == connection) {
                context.disconnectingConnection = null;
            }
            if (context.disconnectingPendingConnection == connection) {
                context.disconnectingPendingConnection = null;
            }
            context.disconnecting = context.disconnectingConnection != null
                || context.disconnectingPendingConnection != null;
            context.connecting = false;
            context.level = null;
            context.player = null;
            context.gameMode = null;
            if (connection.isMemoryConnection()) {
                context.localServer = false;
                if (client != null && context.id() == TabRegistry.activeId()) {
                    ((MinecraftAccessor) client).tabby$setLocalServer(false);
                }
            }
            if (context.levelRenderer != null) {
                context.levelRenderer.setLevel(null);
            }

            LOGGER.info(
                "Cleared disconnected connection state for tab {} ({})",
                context.id(),
                context.title()
            );
        }

        ConnectionOwnershipRegistry.removeConnection(connection, disconnectedListener);
    }

    static void onConnectionCreated(Connection connection) {
        Minecraft client = TabManager.clientInstance();
        if (connection == null) {
            return;
        }
        int ownerId = uniqueConnectingTabId();
        if (ownerId < 0) {
            ownerId = TabManager.currentContextId();
        }
        ConnectionOwnershipRegistry.registerConnection(connection, ownerId);
    }

    static void onListenerAssigned(Connection connection, PacketListener listener) {
        Minecraft client = TabManager.clientInstance();
        if (connection == null || listener == null) {
            return;
        }

        final int ownerId = ConnectionOwnershipRegistry.registerListener(
            connection,
            listener,
            TabManager.currentContextId()
        );

        Minecraft currentClient = client;
        if (currentClient == null) {
            return;
        }

        currentClient.execute(() -> {
            TabContext context = TabRegistry.get(ownerId);
            if (context == null) {
                return;
            }

            if (listener instanceof ClientCommonPacketListenerImpl) {
                context.localServer = connection.isMemoryConnection();
                context.networkConnection = connection;
                if (listener instanceof TickablePacketListener tickable) {
                    context.packetListener = tickable;
                }
            }
            if (listener instanceof ClientPacketListener playListener) {
                context.connecting = false;
                context.replacingConnection = null;
                context.pendingConnection = null;
                if (context.playerName == null || context.playerName.isBlank()) {
                    context.playerName = currentClient.getUser().getName();
                }
                context.networkConnection = connection;
                context.clientConnection = playListener;
                context.packetListener = playListener;
                context.serverData = playListener.getServerData();
                if (context.serverData != null) {
                    String serverTitle = context.serverData.name;
                    if (serverTitle == null || serverTitle.isBlank()) {
                        serverTitle = context.serverData.ip;
                    }
                    context.setTitle(serverTitle == null || serverTitle.isBlank() ? "Server" : serverTitle);
                }
            }
            LOGGER.debug("Assigned {} to tab {}", listener.getClass().getSimpleName(), ownerId);
        });
    }

    static void tickBackgroundConnections() {
        Minecraft client = TabManager.clientInstance();
        if (client == null) {
            return;
        }

        int selectedId = TabRegistry.activeId();
        Connection nativePendingConnection = ((MinecraftAccessor) client).tabby$getPendingConnection();

        for (TabContext context : TabRegistry.values()) {
            if (context.id() == selectedId) {
                // Vanilla ticks pendingConnection itself, but the login-to-configuration
                // transition can clear it before a player/world exists. Keep that active
                // connection progressing until the play listener has installed the world.
                if (client.level == null
                    && client.player == null
                    && nativePendingConnection == null
                    && context.networkConnection != null
                    && context.networkConnection.isConnected()) {
                    TabManager.runIn(context.id(), context.networkConnection::tick);
                }
                continue;
            }

            // Tick the retained Connection object even after its Netty channel closes.
            // Connection.tick() owns the one-shot handleDisconnection() callback; skipping
            // isConnected()==false here strands a background tab before its disconnect
            // listener can run, so reconnect policy and per-tab cleanup never get notified.
            // The Connection method guards its own disconnection handling against repeats.
            Connection backgroundConnection = context.networkConnection != null
                ? context.networkConnection
                : context.pendingConnection;
            if (backgroundConnection != null) {
                TabManager.runIn(context.id(), backgroundConnection::tick);
            }
        }
    }
}
