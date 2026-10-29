package dev.matvey.tabby.tab;

import dev.matvey.tabby.mixin.MinecraftAccessor;
import net.minecraft.client.KeyMapping;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the selected tab and routes client-thread packet tasks to their context.
 */
public final class TabManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    private static final Map<Integer, TabContext> TABS = new LinkedHashMap<>();
    private static final Map<Connection, Integer> CONNECTION_OWNERS = new IdentityHashMap<>();
    private static final Map<PacketListener, Integer> LISTENER_OWNERS = new IdentityHashMap<>();
    private static final ThreadLocal<Integer> EXECUTION_CONTEXT = new ThreadLocal<>();
    private static final boolean SMOKE_TEST_ENABLED = "1".equals(System.getenv("TABBY_SMOKE_TEST"));

    private static Minecraft client;
    private static volatile int activeId = -1;
    private static int nextId;
    private static int smokeTestTicks;
    private static int blockedSingleplayerWorldStartCount;
    private static final List<String> RESTORE_QUEUE = new ArrayList<>();
    private static int restoreTickDelay;

    private TabManager() {
    }

    public static void initialize(Minecraft minecraft) {
        if (client != null) {
            return;
        }

        client = minecraft;
        blockedSingleplayerWorldStartCount = 0;
        restoreTickDelay = 0;
        RESTORE_QUEUE.clear();
        TabbyConfig.load();
        if (TabbyConfig.restoreServerAddresses() && !NetworkSmokeTest.isEnabled()) {
            RESTORE_QUEUE.addAll(TabbyConfig.restoredServerAddresses());
            if (!RESTORE_QUEUE.isEmpty()) {
                LOGGER.info("Restoring {} server address tab(s) from config", RESTORE_QUEUE.size());
            }
        }
        TabContext initial = TabContext.capture(client, nextId++, "Main Menu");
        TABS.put(initial.id(), initial);
        activeId = initial.id();

        TabBar.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(TabManager::onClientTick);
        LOGGER.debug("Initialized tab context {}", initial.id());
    }

    private static void onClientTick(Minecraft minecraft) {
        if (NetworkSmokeTest.isEnabled()) {
            NetworkSmokeTest.tick(minecraft);
        } else {
            runPrototypeSmokeTestTick();
        }

        tickRestoredServerAddresses(minecraft);
        tickBackgroundConnections();
    }

    private static void runPrototypeSmokeTestTick() {
        if (!SMOKE_TEST_ENABLED) {
            return;
        }

        smokeTestTicks++;
        if (smokeTestTicks == 20) {
            createTab();
        } else if (smokeTestTicks == 40) {
            switchToNext();
            Screen activeScreen = client.screen;
            runIn(1, () -> client.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));
            if (client.screen != activeScreen) {
                throw new IllegalStateException("Background screen change replaced the active screen");
            }
        } else if (smokeTestTicks == 60 || smokeTestTicks == 80) {
            switchToNext();
        } else if (smokeTestTicks == 100) {
            if (TABS.size() != 2 || activeId != 0) {
                throw new IllegalStateException("Tabby prototype context-switch smoke test failed");
            }
            LOGGER.info("Tabby prototype context-switch smoke test passed.");
        }
    }

    public static boolean interceptBackgroundScreenChange(Screen screen) {
        if (client == null) {
            return false;
        }

        int contextId = currentContextId();
        if (contextId < 0 || contextId == activeId) {
            return false;
        }

        TabContext context = TABS.get(contextId);
        if (context == null) {
            return false;
        }

        context.screen = screen;
        LOGGER.debug("Stored background screen {} for tab {}", screen, contextId);
        return true;
    }

    public static boolean isBackgroundContext() {
        Integer executionId = EXECUTION_CONTEXT.get();
        return executionId != null && executionId != activeId;
    }

    public static boolean shouldCaptureScreenFromClient() {
        if (client == null) {
            return true;
        }

        Integer executionId = EXECUTION_CONTEXT.get();
        return executionId == null || executionId == activeId;
    }

    public static void captureCurrentState() {
        if (client == null) {
            return;
        }

        TabContext context = TABS.get(currentContextId());
        if (context != null) {
            context.captureFrom(client);
        }
    }

    public static void createTab() {
        if (client == null) {
            return;
        }
        if (!TABS.isEmpty() && TABS.size() - 1 >= TabbyConfig.maxBackgroundTabs()) {
            SystemToast.add(
                client.getToastManager(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("Tabby"),
                Component.literal("Background tab limit reached. Close a tab first.")
            );
            LOGGER.debug("Tab creation rejected: background-tab limit {} reached", TabbyConfig.maxBackgroundTabs());
            return;
        }

        TabContext current = TABS.get(activeId);
        if (current != null) {
            current.captureFrom(client);
        }

        TabContext context = TabContext.createEmpty(client, nextId++);
        TABS.put(context.id(), context);
        switchTo(context.id());
    }

    /**
     * Kept for the network smoke test; normal UI and shortcuts use createTab().
     */
    private static void tickRestoredServerAddresses(Minecraft minecraft) {
        if (client == null || NetworkSmokeTest.isEnabled() || RESTORE_QUEUE.isEmpty()) {
            return;
        }

        if (++restoreTickDelay < 20) {
            return;
        }
        restoreTickDelay = 0;

        if (TABS.size() - 1 >= TabbyConfig.maxBackgroundTabs()) {
            LOGGER.warn("Stopped restoring server tabs because the configured background-tab limit was reached");
            RESTORE_QUEUE.clear();
            return;
        }

        String address = RESTORE_QUEUE.remove(0);
        try {
            createTab();
            TabContext context = TABS.get(activeId);
            if (context == null) {
                return;
            }

            ServerData serverData = new ServerData(address, address, ServerData.Type.OTHER);
            context.serverData = serverData;
            context.setTitle(address);
            ConnectScreen.startConnecting(
                client.screen,
                minecraft,
                ServerAddress.parseString(address),
                serverData,
                false,
                null
            );
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to restore Tabby server address {}", address, exception);
        }
    }

    public static void createTestTab() {
        createTab();
    }

    public static void togglePinned(int tabId) {
        TabContext context = TABS.get(tabId);
        if (context == null) {
            return;
        }

        context.pinned = !context.pinned;
        moveTab(tabId, context.pinned ? 0 : TABS.size() - 1);
        LOGGER.debug("Tab {} pin state changed to {}", tabId, context.pinned);
    }

    public static void moveTab(int tabId, int targetIndex) {
        if (!TABS.containsKey(tabId) || TABS.size() < 2) {
            return;
        }

        List<TabContext> ordered = new ArrayList<>(TABS.values());
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
        TABS.clear();
        for (TabContext context : ordered) {
            TABS.put(context.id(), context);
        }
    }

    public static void closeTab(int tabId) {
        if (client == null || !TABS.containsKey(tabId)) {
            return;
        }

        if (tabId == activeId && TABS.size() > 1) {
            List<Integer> ids = new ArrayList<>(TABS.keySet());
            int currentIndex = ids.indexOf(tabId);
            switchTo(ids.get((currentIndex + 1) % ids.size()));
        }

        TabContext closing = TABS.remove(tabId);
        if (closing == null) {
            return;
        }
        disposeTab(closing, true);

        if (TABS.isEmpty()) {
            TabContext menu = TabContext.createEmpty(client, nextId++);
            TABS.put(menu.id(), menu);
            activeId = menu.id();
            menu.applyTo(client, false);
            client.setScreen(menu.screen);
            menu.captureFrom(client);
            LOGGER.debug("Closed final tab; returned to main menu context {}", menu.id());
        } else if (activeId == tabId) {
            switchTo(TABS.keySet().iterator().next());
        }
    }

    private static void disposeTab(TabContext context, boolean closeRenderer) {
        int id = context.id();

        synchronized (CONNECTION_OWNERS) {
            CONNECTION_OWNERS.entrySet().removeIf(entry -> entry.getValue() == id);
        }
        synchronized (LISTENER_OWNERS) {
            LISTENER_OWNERS.entrySet().removeIf(entry -> entry.getValue() == id);
        }

        stopIntegratedServerForTab(context);

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

    private static void stopIntegratedServerForTab(TabContext context) {
        if (client == null || !context.localServer) {
            return;
        }

        boolean anotherTabOwnsLocalWorld = TABS.values().stream()
            .anyMatch(other -> other.id() != context.id() && other.localServer);
        if (anotherTabOwnsLocalWorld) {
            return;
        }

        MinecraftAccessor accessor = (MinecraftAccessor) client;
        IntegratedServer server = accessor.tabby$getSingleplayerServer();
        if (server != null) {
            accessor.tabby$setSingleplayerServer(null);
            server.halt(false);
            LOGGER.debug("Stopped integrated server owned by tab {}", context.id());
        }
        accessor.tabby$setLocalServer(false);
        context.localServer = false;
    }

    public static void shutdown() {
        Minecraft shuttingDownClient = client;
        if (shuttingDownClient == null) {
            return;
        }

        LOGGER.debug("Shutting down Tabby; closing {} tab contexts", TABS.size());
        if (TabbyConfig.restoreServerAddresses()) {
            List<String> addresses = new ArrayList<>();
            for (TabContext context : TABS.values()) {
                ServerData serverData = context.serverData;
                if (!context.localServer && serverData != null && serverData.ip != null && !serverData.ip.isBlank()) {
                    addresses.add(serverData.ip);
                }
            }
            TabbyConfig.saveRestoredServerAddresses(addresses);
        }
        RESTORE_QUEUE.clear();
        KeyMapping.releaseAll();
        shuttingDownClient.getSoundManager().stop();

        for (TabContext context : List.copyOf(TABS.values())) {
            disposeTab(context, context.id() != activeId);
        }

        TABS.clear();
        synchronized (CONNECTION_OWNERS) {
            CONNECTION_OWNERS.clear();
        }
        synchronized (LISTENER_OWNERS) {
            LISTENER_OWNERS.clear();
        }
        EXECUTION_CONTEXT.remove();
        activeId = -1;
        client = null;
    }

    public static boolean hasInactiveSingleplayerWorld() {
        if (client == null) {
            return false;
        }
        return TABS.values().stream().anyMatch(context ->
            context.id() != activeId
                && context.localServer
                && (context.level != null
                    || context.pendingConnection != null
                    || context.connecting
                    || context.networkConnection != null
                        && (context.networkConnection.isConnected() || context.networkConnection.isConnecting()))
        );
    }

    public static boolean shouldBlockSingleplayerWorldStart() {
        return hasInactiveSingleplayerWorld();
    }

    public static boolean shouldPreserveIntegratedServerDuringDisconnect() {
        if (client == null || !hasInactiveSingleplayerWorld()) {
            return false;
        }
        TabContext active = TABS.get(activeId);
        return active != null && !active.localServer
            && ((MinecraftAccessor) client).tabby$getSingleplayerServer() != null;
    }

    public static void showSingleplayerWorldBlockedNotice() {
        if (client == null) {
            return;
        }
        blockedSingleplayerWorldStartCount++;
        SystemToast.add(
            client.getToastManager(),
            SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
            Component.literal("Tabby"),
            Component.literal("A single-player world is already open in another tab.")
        );
        client.setScreen(new TitleScreen());
    }

    public static int blockedSingleplayerWorldStartCount() {
        return blockedSingleplayerWorldStartCount;
    }

    public static boolean handleShortcut(int key, int modifiers) {
        if (client == null || (modifiers & GLFW.GLFW_MOD_CONTROL) == 0) {
            return false;
        }

        if (key == TabbyConfig.keyCreateTab()) {
            createTab();
            return true;
        }
        if (key == TabbyConfig.keyCloseTab()) {
            closeTab(activeId);
            return true;
        }
        if (key == TabbyConfig.keyNextTab()) {
            switchToNext();
            return true;
        }
        int firstTabKey = TabbyConfig.keySelectFirstTab();
        if (key >= firstTabKey && key < firstTabKey + 9) {
            switchToIndex(key - firstTabKey);
            return true;
        }
        return false;
    }

    private static void switchToIndex(int index) {
        List<Integer> ids = new ArrayList<>(TABS.keySet());
        if (index >= 0 && index < ids.size()) {
            switchTo(ids.get(index));
        }
    }

    public static void switchToNext() {
        if (TABS.size() < 2) {
            return;
        }

        List<Integer> ids = new ArrayList<>(TABS.keySet());
        int currentIndex = ids.indexOf(activeId);
        switchTo(ids.get((currentIndex + 1) % ids.size()));
    }

    public static void switchTo(int tabId) {
        if (client == null || tabId == activeId || !TABS.containsKey(tabId)) {
            return;
        }

        TabContext previous = TABS.get(activeId);
        TabContext target = TABS.get(tabId);
        if (previous != null) {
            previous.captureFrom(client);
        }

        try {
            KeyMapping.releaseAll();
            client.getSoundManager().stop();
            client.mouseHandler.setIgnoreFirstMove();

            target.applyTo(client, false);
            activeId = tabId;
            // LevelRenderer.setLevel() invokes allChanged(), rebuilding world chunks for this tab.
            target.levelRenderer.setLevel(target.level);
            target.particleEngine.setLevel(target.level);
            client.setScreen(target.screen);
            KeyMapping.releaseAll();
            client.mouseHandler.setIgnoreFirstMove();
            target.captureFrom(client);
            LOGGER.debug("Switched active tab to {} ({})", target.id(), target.title());
        } catch (RuntimeException | Error exception) {
            LOGGER.debug("Failed to switch to tab {}", tabId, exception);
            if (previous != null) {
                activeId = previous.id();
                previous.applyTo(client, false);
                client.setScreen(previous.screen);
                KeyMapping.releaseAll();
                client.mouseHandler.setIgnoreFirstMove();
            }
            throw exception;
        }
    }

    public static Runnable wrapPacketTask(PacketListener listener, Runnable task) {
        if (client == null) {
            return task;
        }

        int ownerId = ownerIdFor(listener);
        if (ownerId < 0) {
            return task;
        }

        // Capture the owner when the task is queued, not the active tab at arrival.
        // The active tab may change before Minecraft executes the queued packet task.
        return () -> runIn(ownerId, task);
    }

    public static void runIn(int tabId, Runnable task) {
        if (client == null || task == null) {
            if (task != null) {
                task.run();
            }
            return;
        }

        int previousId = currentContextId();
        if (previousId == tabId) {
            task.run();
            return;
        }

        TabContext previous = TABS.get(previousId);
        TabContext target = TABS.get(tabId);
        if (target == null) {
            LOGGER.debug("Ignoring task for unknown tab {}", tabId);
            return;
        }

        if (previous != null) {
            previous.captureFrom(client);
        }

        Integer previousExecutionId = EXECUTION_CONTEXT.get();
        try {
            EXECUTION_CONTEXT.set(tabId);
            target.applyTo(client, true);
            task.run();
        } catch (RuntimeException | Error exception) {
            LOGGER.debug("Packet task failed in tab {}", tabId, exception);
            throw exception;
        } finally {
            try {
                target.captureFrom(client);
            } finally {
                try {
                    if (previous != null) {
                        previous.applyTo(client, true);
                    }
                } finally {
                    if (previousExecutionId == null) {
                        EXECUTION_CONTEXT.remove();
                    } else {
                        EXECUTION_CONTEXT.set(previousExecutionId);
                    }
                }
            }
        }
    }

    /**
     * Called before vanilla's ConnectScreen disconnects the current connection.
     * Returning true prevents a duplicate attempt from disrupting the existing tab.
     */
    public static boolean handleConnectionAttempt(ServerAddress address, ServerData serverData) {
        if (client == null || address == null || serverData == null) {
            return false;
        }

        int targetId = currentContextId();
        TabContext target = TABS.get(targetId);
        if (target == null) {
            return false;
        }

        String username = client.getUser().getName();
        for (TabContext existing : TABS.values()) {
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

    private static boolean sameServer(ServerData existingData, ServerAddress address) {
        if (existingData == null || existingData.ip == null || existingData.ip.isBlank()) {
            return false;
        }

        ServerAddress existingAddress = ServerAddress.parseString(existingData.ip);
        return existingAddress.getPort() == address.getPort()
            && existingAddress.getHost().equalsIgnoreCase(address.getHost());
    }

    private static boolean hasLiveConnectionAttempt(TabContext context) {
        return context.connecting
            || context.networkConnection != null
                && (context.networkConnection.isConnected() || context.networkConnection.isConnecting())
            || context.pendingConnection != null
                && (context.pendingConnection.isConnected() || context.pendingConnection.isConnecting());
    }

    private static int uniqueConnectingTabId() {
        int candidate = -1;
        for (TabContext context : TABS.values()) {
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

    public static boolean onScreenChangeRequested(Screen screen) {
        if (client == null
            || screen == null
            || screen instanceof net.minecraft.client.gui.screens.ConnectScreen
            || screen instanceof net.minecraft.client.gui.screens.ProgressScreen) {
            // ConnectScreen.startConnecting calls Minecraft.disconnect(), which briefly installs
            // a ProgressScreen before it installs the new ConnectScreen. Keep the attempt marker
            // through that vanilla transition so connection ownership remains bound to this tab.
            return false;
        }

        TabContext context = TABS.get(currentContextId());
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

    public static void onConnectionDisconnected(Connection connection) {
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

    private static void cleanupDisconnectedConnection(Connection connection) {
        PacketListener disconnectedListener = connection.getPacketListener();

        for (TabContext context : List.copyOf(TABS.values())) {
            boolean replacingOldConnection = context.connecting && context.replacingConnection == connection;
            boolean ownsConnection = context.networkConnection == connection
                || context.pendingConnection == connection
                || context.replacingConnection == connection
                || context.clientConnection != null
                    && context.clientConnection.getConnection() == connection;
            if (!ownsConnection) {
                continue;
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
            context.connecting = false;
            context.level = null;
            context.player = null;
            context.gameMode = null;
            if (connection.isMemoryConnection()) {
                context.localServer = false;
                if (client != null && context.id() == activeId) {
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

        synchronized (CONNECTION_OWNERS) {
            CONNECTION_OWNERS.remove(connection);
        }
        if (disconnectedListener != null) {
            synchronized (LISTENER_OWNERS) {
                LISTENER_OWNERS.remove(disconnectedListener);
            }
        }
    }

    public static void onConnectionCreated(Connection connection) {
        if (connection == null) {
            return;
        }
        int ownerId = uniqueConnectingTabId();
        if (ownerId < 0) {
            ownerId = currentContextId();
        }
        synchronized (CONNECTION_OWNERS) {
            CONNECTION_OWNERS.putIfAbsent(connection, ownerId);
        }
    }

    public static void onListenerAssigned(Connection connection, PacketListener listener) {
        if (connection == null || listener == null) {
            return;
        }

        final int ownerId;
        synchronized (CONNECTION_OWNERS) {
            ownerId = CONNECTION_OWNERS.computeIfAbsent(connection, ignored -> currentContextId());
        }
        synchronized (LISTENER_OWNERS) {
            LISTENER_OWNERS.put(listener, ownerId);
        }

        if (listener instanceof TabOwnedListener owned) {
            owned.tabby$setTabId(ownerId);
        }

        Minecraft currentClient = client;
        if (currentClient == null) {
            return;
        }

        currentClient.execute(() -> {
            TabContext context = TABS.get(ownerId);
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

    private static int ownerIdFor(PacketListener listener) {
        if (listener instanceof TabOwnedListener owned && owned.tabby$getTabId() >= 0) {
            return owned.tabby$getTabId();
        }

        synchronized (LISTENER_OWNERS) {
            return LISTENER_OWNERS.getOrDefault(listener, -1);
        }
    }

    private static void tickBackgroundConnections() {
        if (client == null) {
            return;
        }

        int selectedId = activeId;
        Connection nativePendingConnection = ((MinecraftAccessor) client).tabby$getPendingConnection();

        for (TabContext context : List.copyOf(TABS.values())) {
            if (context.id() == selectedId) {
                // Vanilla ticks pendingConnection itself, but the login-to-configuration
                // transition can clear it before a player/world exists. Keep that active
                // connection progressing until the play listener has installed the world.
                if (client.level == null
                    && client.player == null
                    && nativePendingConnection == null
                    && context.networkConnection != null
                    && context.networkConnection.isConnected()) {
                    context.networkConnection.tick();
                }
                continue;
            }

            if (context.networkConnection != null && context.networkConnection.isConnected()) {
                runIn(context.id(), context.networkConnection::tick);
            } else if (context.pendingConnection != null && context.pendingConnection.isConnected()) {
                runIn(context.id(), context.pendingConnection::tick);
            }
        }
    }

    private static int currentContextId() {
        Integer id = EXECUTION_CONTEXT.get();
        return id == null ? activeId : id;
    }

    public static int activeId() {
        return activeId;
    }

    public static List<TabContext> contexts() {
        return List.copyOf(TABS.values());
    }
}
