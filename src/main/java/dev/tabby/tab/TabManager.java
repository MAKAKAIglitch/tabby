package dev.tabby.tab;

import dev.tabby.mixin.MinecraftAccessor;
import dev.tabby.mixin.ClientLevelAccessor;
import dev.tabby.mixin.LevelRendererAccessor;
import dev.tabby.mixin.ParticleEngineAccessor;
import net.minecraft.client.KeyMapping;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.client.gui.screens.PauseScreen;
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
import java.util.ArrayDeque;
import java.util.List;

/**
 * Coordinates tab lifecycle and exposes the stable API used by mixins and UI.
 */
public final class TabManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    private static final ThreadLocal<ArrayDeque<Integer>> CLIENT_LEVEL_TRANSITIONS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Integer>> CLIENT_LOGIN_HANDLERS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Integer> CLIENT_DISCONNECT_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final boolean SMOKE_TEST_ENABLED = "1".equals(System.getenv("TABBY_SMOKE_TEST"));

    private static Minecraft client;
    private static int smokeTestTicks;
    private static int blockedSingleplayerWorldStartCount;
    private static final List<String> RESTORE_QUEUE = new ArrayList<>();
    private static int restoreTickDelay;
    private static int memoryRefreshTicks;

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
        if (TabbyConfig.restoreServerAddresses() && !NetworkSmokeTest.isEnabled() && !CompatibilitySmokeTest.isEnabled()) {
            RESTORE_QUEUE.addAll(TabbyConfig.restoredServerAddresses());
            if (!RESTORE_QUEUE.isEmpty()) {
                LOGGER.info("Restoring {} server address tab(s) from config", RESTORE_QUEUE.size());
            }
        }
        TabContext initial = TabContext.capture(client, TabRegistry.nextId(), "Main Menu");
        TabRegistry.put(initial.id(), initial);
        TabRegistry.setActiveId(initial.id());

        TabBar.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(TabManager::onClientTick);
        LOGGER.debug("Initialized tab context {}", initial.id());
    }

    private static void onClientTick(Minecraft minecraft) {
        if (isClientDisconnectInProgress()) {
            return;
        }
        if (isClientLevelTransitioning() || isClientLoginInProgress()) {
            LOGGER.debug("Skipping Tabby maintenance while a client login/world transition is in progress");
            return;
        }

        // Vanilla may run a nested client tick while a packet handler calls setLevel().
        // That packet handler runs under runIn(tabId), so TabRegistry.activeId() still identifies the
        // visible tab, not the context temporarily installed for the packet. Running the
        // background-connection loop here would tick the visible tab against the temporary
        // context and can clear the incoming world's gameMode/player state. Only perform
        // Tabby maintenance from the outer, ordinary client tick.
        Integer executionId = TabContextExecutor.executionId();
        if (executionId != null) {
            LOGGER.debug("Skipping nested client-tick maintenance while processing tab {} packet", executionId);
            return;
        }

        if (NetworkSmokeTest.isEnabled()) {
            NetworkSmokeTest.tick(minecraft);
        } else if (CompatibilitySmokeTest.isEnabled()) {
            CompatibilitySmokeTest.tick(minecraft);
        } else {
            runPrototypeSmokeTestTick();
        }

        tickRestoredServerAddresses(minecraft);
        tickBackgroundConnections();
        if (TabbyConfig.showHudInfo()) {
            if (++memoryRefreshTicks >= 20) {
                memoryRefreshTicks = 0;
                TabMemoryUsage.refresh(List.copyOf(TabRegistry.values()));
            }
        } else {
            // The diagnostic HUD is off by default; skip periodic chunk/entity counting
            // until the user explicitly enables the plaque in Mod Menu.
            memoryRefreshTicks = 0;
        }
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
            if (TabRegistry.size() != 2 || TabRegistry.activeId() != 0) {
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
        if (contextId < 0 || contextId == TabRegistry.activeId()) {
            return false;
        }

        TabContext context = TabRegistry.get(contextId);
        if (context == null) {
            return false;
        }

        context.screen = screen;
        LOGGER.debug("Stored background screen {} for tab {}", screen, contextId);
        return true;
    }

    /**
     * Only the selected tab's LevelRenderer may enter the world-rendering pipeline.
     */
    public static boolean shouldRenderRenderer(LevelRenderer renderer) {
        return TabRendererCompatibility.shouldRenderRenderer(
            renderer,
            client != null,
            TabRegistry.activeId(),
            TabRegistry.get(TabRegistry.activeId()),
            isBackgroundContext()
        );
    }

    public static boolean shouldDeferVoxyRendererCreation(LevelRenderer renderer) {
        return TabRendererCompatibility.shouldDeferVoxyRendererCreation(
            renderer,
            client != null,
            TabRegistry.activeId(),
            TabRegistry.get(TabRegistry.activeId()),
            isBackgroundContext()
        );
    }

    public static TabContext activeContext() {
        return TabRegistry.get(TabRegistry.activeId());
    }

    public static boolean isBackgroundContext() {
        return TabContextExecutor.isBackgroundContext(TabRegistry.activeId());
    }

    public static boolean shouldCaptureScreenFromClient() {
        if (client == null) {
            return true;
        }

        Integer executionId = TabContextExecutor.executionId();
        return executionId == null || executionId == TabRegistry.activeId();
    }

    public static void captureCurrentState() {
        if (client == null || isClientDisconnectInProgress() || isClientLevelTransitioning() || isClientLoginInProgress()) {
            // Minecraft.setLevel() and ClientPacketListener.handleLogin() contain nested
            // screen ticks. Do not capture intentionally transient globals until the full
            // login packet handler has created and installed its player.
            return;
        }

        // Vanilla intentionally clears client.level/player while it disconnects the
        // previous world and loads a replacement. Do not capture or "repair" that
        // temporary state: the old listener may already have cleared its level.
        if (TabContextExecutor.executionId() == null) {
            TabContext active = TabRegistry.get(TabRegistry.activeId());
            if (active != null && active.worldTransitioning) {
                if (++active.worldTransitionTicks <= 1200) {
                    return;
                }

                active.worldTransitioning = false;
                active.worldTransitionTicks = 0;
                LOGGER.warn(
                    "World transition timed out for tab {}; resuming client-state recovery",
                    active.id()
                );
            }
        }

        if (restoreActiveContextIfForeignState()) {
            return;
        }

        TabContext context = TabRegistry.get(currentContextId());
        if (context != null) {
            context.captureFrom(client);
        }
    }

    /**
     * Brackets vanilla's synchronous disconnect cleanup. Vanilla may run nested
     * screen/tick work while closing a listener; Tabby must not capture half-cleared
     * globals or tick another connection until the disconnect has fully returned.
     */
    public static void beginClientDisconnect(Screen destination) {
        if (client == null) {
            return;
        }

        int depth = CLIENT_DISCONNECT_DEPTH.get();
        CLIENT_DISCONNECT_DEPTH.set(depth + 1);
        if (depth == 0) {
            TabContext context = TabRegistry.get(currentContextId());
            LOGGER.info(
                "Tabby: beginning vanilla disconnect for tab {} (destination={}, localServer={}, memoryConnection={}, inactiveSingleplayer={})",
                context == null ? -1 : context.id(),
                destination == null ? "null" : destination.getClass().getSimpleName(),
                context != null && context.localServer,
                isSingleplayerTab(context),
                hasInactiveSingleplayerWorld()
            );
        }
    }

    public static void endClientDisconnect() {
        int depth = CLIENT_DISCONNECT_DEPTH.get();
        if (depth <= 0) {
            CLIENT_DISCONNECT_DEPTH.remove();
            return;
        }
        if (depth > 1) {
            CLIENT_DISCONNECT_DEPTH.set(depth - 1);
            return;
        }

        CLIENT_DISCONNECT_DEPTH.remove();
        if (client == null) {
            return;
        }

        TabContext context = TabRegistry.get(currentContextId());
        if (context != null) {
            context.captureFrom(client);
            context.worldTransitioning = false;
            context.worldTransitionTicks = 0;
            if (client.getConnection() == null
                && (context.networkConnection == null || !context.networkConnection.isConnected())) {
                context.connecting = false;
            }
            LOGGER.info(
                "Tabby: vanilla disconnect returned for tab {} (level={}, player={}, connectionLive={}, localServer={})",
                context.id(),
                context.level != null,
                context.player != null,
                context.networkConnection != null && context.networkConnection.isConnected(),
                context.localServer
            );
        }
    }

    public static boolean isClientDisconnectInProgress() {
        return CLIENT_DISCONNECT_DEPTH.get() > 0;
    }

    public static void beginWorldTransition(String worldId) {
        if (client == null) {
            return;
        }

        TabContext context = TabRegistry.get(currentContextId());
        if (context == null) {
            return;
        }

        context.worldTransitioning = true;
        context.worldTransitionTicks = 0;
        LOGGER.info("Tabby: starting world transition in tab {} to '{}'", context.id(), worldId);
    }

    /**
     * Brackets the full play-login packet handler, not just Minecraft.setLevel().
     * The player is created only after setLevel() returns, so nested ticks must not run
     * Tabby maintenance between installing the level and completing that creation.
     */
    public static void beginClientLogin(ClientPacketListener listener) {
        if (client == null || listener == null || !client.isSameThread()) {
            return;
        }

        int ownerId = ownerIdFor(listener);
        if (ownerId < 0) {
            ownerId = currentContextId();
        }
        CLIENT_LOGIN_HANDLERS.get().push(ownerId);
        LOGGER.debug("Beginning ClientPacketListener.handleLogin for tab {}", ownerId);
    }

    public static void endClientLogin(ClientPacketListener listener) {
        if (client == null || listener == null || !client.isSameThread()) {
            return;
        }

        ArrayDeque<Integer> handlers = CLIENT_LOGIN_HANDLERS.get();
        int ownerId = handlers.isEmpty() ? ownerIdFor(listener) : handlers.pop();
        if (ownerId < 0) {
            ownerId = currentContextId();
        }
        boolean sameTabStillLoggingIn = handlers.contains(ownerId);
        if (handlers.isEmpty()) {
            CLIENT_LOGIN_HANDLERS.remove();
        }

        TabContext context = TabRegistry.get(ownerId);
        if (context == null) {
            LOGGER.warn("Completed login for unknown Tabby context {}", ownerId);
            return;
        }

        context.captureFrom(client);
        if (!sameTabStillLoggingIn) {
            context.worldTransitioning = false;
            context.worldTransitionTicks = 0;
        }
        LOGGER.debug(
            "Completed ClientPacketListener.handleLogin for tab {} (level={}, player={}, gameMode={})",
            ownerId,
            context.level != null,
            context.player != null,
            context.gameMode != null
        );
    }

    public static boolean isClientLoginInProgress() {
        return !CLIENT_LOGIN_HANDLERS.get().isEmpty();
    }

    public static void beginClientLevelTransition(ClientLevel incomingLevel) {
        if (client == null || incomingLevel == null) {
            return;
        }

        int ownerId = currentContextId();
        ClientPacketListener incomingListener =
            ((ClientLevelAccessor) incomingLevel).tabby$getConnection();
        int listenerOwnerId = ownerIdFor(incomingListener);
        if (listenerOwnerId >= 0) {
            ownerId = listenerOwnerId;
        }

        ArrayDeque<Integer> transitions = CLIENT_LEVEL_TRANSITIONS.get();
        transitions.push(ownerId);
        TabContext context = TabRegistry.get(ownerId);
        if (context != null) {
            context.worldTransitioning = true;
            context.worldTransitionTicks = 0;
        }
        LOGGER.debug("Beginning level installation for tab {}", ownerId);
    }

    public static boolean isClientLevelTransitioning() {
        return !CLIENT_LEVEL_TRANSITIONS.get().isEmpty();
    }

    public static void onClientLevelInstalled(ClientLevel installedLevel) {
        if (client == null) {
            return;
        }

        ArrayDeque<Integer> transitions = CLIENT_LEVEL_TRANSITIONS.get();
        int ownerId = transitions.isEmpty() ? currentContextId() : transitions.pop();
        boolean sameTabStillTransitioning = transitions.contains(ownerId);
        if (transitions.isEmpty()) {
            CLIENT_LEVEL_TRANSITIONS.remove();
        }

        if (installedLevel == null) {
            return;
        }

        ClientPacketListener listener =
            ((ClientLevelAccessor) installedLevel).tabby$getConnection();
        int listenerOwnerId = ownerIdFor(listener);
        if (listenerOwnerId >= 0) {
            ownerId = listenerOwnerId;
        }

        TabContext context = TabRegistry.get(ownerId);
        if (context == null) {
            LOGGER.warn("Installed a ClientLevel for unknown tab {}; active tab is {}", ownerId, TabRegistry.activeId());
            return;
        }

        context.level = installedLevel;
        context.player = client.player;
        context.gameMode = client.gameMode;
        context.levelRenderer = client.levelRenderer;
        context.particleEngine = client.particleEngine;
        context.gui = client.gui;
        context.hitResult = client.hitResult;
        context.crosshairPickEntity = client.crosshairPickEntity;
        context.cameraEntity = client.cameraEntity;
        context.clientConnection = listener;
        context.networkConnection = listener.getConnection();
        context.localServer = listener.getConnection().isMemoryConnection();
        context.packetListener = listener;
        context.serverData = listener.getServerData();
        if (!sameTabStillTransitioning) {
            context.worldTransitioning = false;
            context.worldTransitionTicks = 0;
        }
        LOGGER.info("Tabby: world transition completed in tab {}", context.id());
    }

    private static boolean restoreActiveContextIfForeignState() {
        if (TabContextExecutor.executionId() != null) {
            return false;
        }

        TabContext active = TabRegistry.get(TabRegistry.activeId());
        if (active == null || active.worldTransitioning) {
            return false;
        }

        // A connected play tab must never enter an unrelated client tick with no level.
        // This guards other client mods (for example pre-tick event listeners) from seeing
        // a transient null left by a background context switch or renderer transition.
        boolean activeConnectionLive = active.networkConnection != null
            && active.networkConnection.isConnected();
        boolean activeWorldWasLost = activeConnectionLive
            && active.level != null
            && active.player != null
            && client.level == null;
        if (activeWorldWasLost) {
            LOGGER.warn(
                "Restoring missing active world for tab {} before client tick (connection still live)",
                active.id()
            );
            restoreClientGlobals(active);
            return true;
        }

        for (TabContext other : TabRegistry.values()) {
            if (other.id() == active.id()) {
                continue;
            }

            boolean foreignLevel = other.level != null && client.level == other.level;
            boolean foreignPlayer = other.player != null && client.player == other.player;
            boolean foreignGameMode = other.gameMode != null && client.gameMode == other.gameMode;
            boolean foreignRenderer = other.levelRenderer != null && client.levelRenderer == other.levelRenderer;
            if (!foreignLevel && !foreignPlayer && !foreignGameMode && !foreignRenderer) {
                continue;
            }

            LOGGER.debug(
                "Detected foreign global client state from tab {}; restoring active tab {} before tick",
                other.id(),
                active.id()
            );
            restoreClientGlobals(active);
            return true;
        }

        return false;
    }

    private static void restoreClientGlobals(TabContext context) {
        context.applyTo(client, true);
        if (context.levelRenderer != null
            && ((LevelRendererAccessor) context.levelRenderer).tabby$getLevel() != context.level) {
            context.levelRenderer.setLevel(context.level);
        }
        if (context.levelRenderer != null) {
            client.entityRenderDispatcher.setLevel(context.level);
        }
        if (context.particleEngine != null
            && ((ParticleEngineAccessor) context.particleEngine).tabby$getLevel() != context.level) {
            context.particleEngine.setLevel(context.level);
        }
        TabRendererCompatibility.resumeVoxyRenderer(context.levelRenderer, context.level, context.id());
    }

    public static void createTab() {
        if (client == null) {
            return;
        }
        if (!TabRegistry.isEmpty() && TabRegistry.size() - 1 >= TabbyConfig.maxBackgroundTabs()) {
            SystemToast.add(
                client.getToastManager(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("Tabby"),
                Component.literal("Background tab limit reached. Close a tab first.")
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
        switchTo(context.id());
    }

    /**
     * Kept for the network smoke test; normal UI and shortcuts use createTab().
     */
    private static void tickRestoredServerAddresses(Minecraft minecraft) {
        if (client == null || NetworkSmokeTest.isEnabled() || CompatibilitySmokeTest.isEnabled() || RESTORE_QUEUE.isEmpty()) {
            return;
        }

        if (++restoreTickDelay < 20) {
            return;
        }
        restoreTickDelay = 0;

        if (TabRegistry.size() - 1 >= TabbyConfig.maxBackgroundTabs()) {
            LOGGER.warn("Stopped restoring server tabs because the configured background-tab limit was reached");
            RESTORE_QUEUE.clear();
            return;
        }

        String address = RESTORE_QUEUE.remove(0);
        try {
            createTab();
            TabContext context = TabRegistry.get(TabRegistry.activeId());
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
        TabContext context = TabRegistry.get(tabId);
        if (context == null) {
            return;
        }

        context.pinned = !context.pinned;
        moveTab(tabId, context.pinned ? 0 : TabRegistry.size());
        LOGGER.debug("Tab {} pin state changed to {}", tabId, context.pinned);
    }

    public static void moveTab(int tabId, int targetIndex) {
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

    /**
     * Handles the PauseScreen's Disconnect button without invoking Minecraft's
     * global disconnect routine when more than one tab is open.
     */
    public static boolean disconnectActiveTabFromPauseScreen() {
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
    public static boolean handleListenerDisconnect(
        ClientCommonPacketListenerImpl listener,
        DisconnectionDetails details
    ) {
        if (client == null || listener == null) {
            return false;
        }

        int ownerId = ownerIdFor(listener);
        if (ownerId < 0) {
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
        if (TabRegistry.size() <= 1) {
            return false;
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

        Minecraft currentClient = client;
        Runnable disconnect = () -> disconnectTabToMenu(
            ownerId,
            details.reason(),
            false
        );
        if (currentClient.isSameThread()) {
            if (currentContextId() == ownerId) {
                disconnect.run();
            } else {
                runIn(ownerId, disconnect);
                restoreVisibleSharedRenderState(ownerId);
            }
        } else {
            currentClient.execute(() -> {
                if (currentContextId() == ownerId) {
                    disconnect.run();
                } else {
                    runIn(ownerId, disconnect);
                    restoreVisibleSharedRenderState(ownerId);
                }
            });
        }
        LOGGER.info("Tabby: scoped disconnect callback to tab {} instead of the global client", ownerId);
        return true;
    }

    private static boolean hasTabConnection(TabContext context) {
        return context.level != null
            || context.player != null
            || context.clientConnection != null
            || context.networkConnection != null
            || context.pendingConnection != null
            || context.connecting
            || context.localServer;
    }

    private static boolean disconnectTabToMenu(int tabId, Component reason, boolean closeConnection) {
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

        Runnable operation = () -> disconnectInstalledTabToMenu(context, reason, closeConnection);
        if (currentContextId() == tabId) {
            operation.run();
        } else {
            runIn(tabId, operation);
            restoreVisibleSharedRenderState(tabId);
        }
        return true;
    }

    private static void disconnectInstalledTabToMenu(
        TabContext context,
        Component reason,
        boolean closeConnection
    ) {
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
            stopIntegratedServerForTab(context);
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
        // shared render state after runIn() returns.
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

        LOGGER.info(
            "Tabby: disconnected only tab {} (connectionCloseRequested={}, otherTabs={})",
            context.id(),
            closeConnection,
            TabRegistry.size() - 1
        );
    }

    private static void restoreVisibleSharedRenderState(int disconnectedTabId) {
        if (client == null || disconnectedTabId == TabRegistry.activeId()) {
            return;
        }

        TabContext visible = TabRegistry.get(TabRegistry.activeId());
        if (visible != null) {
            restoreClientGlobals(visible);
        }
    }

    public static void closeTab(int tabId) {
        if (client == null || !TabRegistry.contains(tabId)) {
            return;
        }

        if (tabId == TabRegistry.activeId() && TabRegistry.size() > 1) {
            List<Integer> ids = new ArrayList<>(TabRegistry.ids());
            int currentIndex = ids.indexOf(tabId);
            switchTo(ids.get((currentIndex + 1) % ids.size()));
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
            switchTo(TabRegistry.firstId());
        }
    }

    private static void disposeTab(TabContext context, boolean closeRenderer) {
        int id = context.id();
        TabMemoryUsage.remove(id);

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

    private static boolean isSingleplayerTab(TabContext context) {
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

    private static boolean hasSingleplayerState(TabContext context) {
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

    private static void stopIntegratedServerForTab(TabContext context) {
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

    public static void shutdown() {
        Minecraft shuttingDownClient = client;
        if (shuttingDownClient == null) {
            return;
        }

        LOGGER.debug("Shutting down Tabby; closing {} tab contexts", TabRegistry.size());
        if (TabbyConfig.restoreServerAddresses()) {
            List<String> addresses = new ArrayList<>();
            for (TabContext context : TabRegistry.values()) {
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

        for (TabContext context : List.copyOf(TabRegistry.values())) {
            disposeTab(context, context.id() != TabRegistry.activeId());
        }

        TabRegistry.clear();
        TabMemoryUsage.clear();
        ConnectionOwnershipRegistry.clear();
        TabContextExecutor.clearCurrentThread();
        TabRegistry.setActiveId(-1);
        client = null;
    }

    public static boolean hasInactiveSingleplayerWorld() {
        if (client == null) {
            return false;
        }
        return TabRegistry.values().stream().anyMatch(context ->
            context.id() != TabRegistry.activeId()
                && isSingleplayerTab(context)
                && hasSingleplayerState(context)
        );
    }

    public static boolean shouldBlockSingleplayerWorldStart() {
        return hasInactiveSingleplayerWorld();
    }

    public static boolean shouldPreserveIntegratedServerDuringDisconnect() {
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
            closeTab(TabRegistry.activeId());
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
        List<Integer> ids = new ArrayList<>(TabRegistry.ids());
        if (index >= 0 && index < ids.size()) {
            switchTo(ids.get(index));
        }
    }

    public static void switchToNext() {
        if (TabRegistry.size() < 2) {
            return;
        }

        List<Integer> ids = new ArrayList<>(TabRegistry.ids());
        int currentIndex = ids.indexOf(TabRegistry.activeId());
        switchTo(ids.get((currentIndex + 1) % ids.size()));
    }

    public static void switchTo(int tabId) {
        if (client == null || tabId == TabRegistry.activeId() || !TabRegistry.contains(tabId)) {
            return;
        }

        boolean pauseMenuRequested = client.screen instanceof PauseScreen;
        TabContext previous = TabRegistry.get(TabRegistry.activeId());
        TabContext target = TabRegistry.get(tabId);
        if (previous != null) {
            previous.captureFrom(client);
        }

        try {
            KeyMapping.releaseAll();
            client.getSoundManager().stop();
            client.mouseHandler.setIgnoreFirstMove();

            if (previous != null) {
                TabRendererCompatibility.suspendVoxyRenderer(previous.levelRenderer, previous.id());
            }
            target.applyTo(client, false);
            TabRegistry.setActiveId(tabId);
            // Keep already-built chunk render state when returning to a known tab.
            // setLevel() calls allChanged(), so only call it if the renderer has a different world.
            if (((LevelRendererAccessor) target.levelRenderer).tabby$getLevel() != target.level) {
                target.levelRenderer.setLevel(target.level);
            } else {
                // The entity dispatcher is shared by Minecraft, unlike LevelRenderer.
                client.entityRenderDispatcher.setLevel(target.level);
            }
            if (((ParticleEngineAccessor) target.particleEngine).tabby$getLevel() != target.level) {
                target.particleEngine.setLevel(target.level);
            }
            TabRendererCompatibility.resumeVoxyRenderer(target.levelRenderer, target.level, target.id());
            if (pauseMenuRequested && target.level != null) {
                // The source context may have a stale ConnectScreen/overlay saved from login.
                // Switching from PauseScreen should consistently land on this world's pause menu.
                target.screen = new PauseScreen(true);
            }
            client.setScreen(target.screen);
            KeyMapping.releaseAll();
            client.mouseHandler.setIgnoreFirstMove();
            target.captureFrom(client);
            LOGGER.debug("Switched active tab to {} ({})", target.id(), target.title());
        } catch (RuntimeException | Error exception) {
            LOGGER.debug("Failed to switch to tab {}", tabId, exception);
            if (previous != null) {
                TabRegistry.setActiveId(previous.id());
                previous.applyTo(client, false);
                TabRendererCompatibility.resumeVoxyRenderer(previous.levelRenderer, previous.level, previous.id());
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
        TabContextExecutor.runIn(client, TabRegistry.activeId(), TabRegistry.view(), tabId, task);
    }

    /**
     * Called before vanilla's ConnectScreen disconnects the current connection.
     * Returning true prevents a duplicate attempt from disrupting the existing tab.
     */
    /**
     * Vanilla disconnects the global client before every server connection attempt.
     * That is correct only when the currently selected tab owns the world being
     * disconnected. A freshly-created empty tab must not tear down an integrated
     * server or client world retained by another tab.
     */
    public static void disconnectCurrentTabBeforeConnecting(Minecraft minecraft) {
        if (client == null || minecraft != client) {
            minecraft.disconnect();
            return;
        }

        TabContext context = TabRegistry.get(currentContextId());
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

    public static boolean handleConnectionAttempt(ServerAddress address, ServerData serverData) {
        if (client == null || address == null || serverData == null) {
            return false;
        }

        int targetId = currentContextId();
        TabContext target = TabRegistry.get(targetId);
        if (target == null) {
            return false;
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

        TabContext context = TabRegistry.get(currentContextId());
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

        for (TabContext context : List.copyOf(TabRegistry.values())) {
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

    public static void onConnectionCreated(Connection connection) {
        if (connection == null) {
            return;
        }
        int ownerId = uniqueConnectingTabId();
        if (ownerId < 0) {
            ownerId = currentContextId();
        }
        ConnectionOwnershipRegistry.registerConnection(connection, ownerId);
    }

    public static void onListenerAssigned(Connection connection, PacketListener listener) {
        if (connection == null || listener == null) {
            return;
        }

        final int ownerId = ConnectionOwnershipRegistry.registerListener(
            connection,
            listener,
            currentContextId()
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

    private static int ownerIdFor(PacketListener listener) {
        return ConnectionOwnershipRegistry.ownerIdFor(listener);
    }

    private static void tickBackgroundConnections() {
        if (client == null) {
            return;
        }

        int selectedId = TabRegistry.activeId();
        Connection nativePendingConnection = ((MinecraftAccessor) client).tabby$getPendingConnection();

        for (TabContext context : List.copyOf(TabRegistry.values())) {
            if (context.id() == selectedId) {
                // Vanilla ticks pendingConnection itself, but the login-to-configuration
                // transition can clear it before a player/world exists. Keep that active
                // connection progressing until the play listener has installed the world.
                if (client.level == null
                    && client.player == null
                    && nativePendingConnection == null
                    && context.networkConnection != null
                    && context.networkConnection.isConnected()) {
                    runIn(context.id(), context.networkConnection::tick);
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

    /**
     * Returns whether Minecraft's current global fields are installed for the given tab.
     * Context snapshots must never capture globals belonging to another tab.
     */
    public static boolean isContextInstalled(int tabId) {
        return TabContextExecutor.isContextInstalled(client != null, TabRegistry.activeId(), tabId);
    }

    private static int currentContextId() {
        return TabContextExecutor.currentContextId(TabRegistry.activeId());
    }

    public static int activeId() {
        return TabRegistry.activeId();
    }

    public static List<TabContext> contexts() {
        return List.copyOf(TabRegistry.values());
    }
}
