package dev.tabby.tab;

import dev.tabby.mixin.MinecraftAccessor;
import dev.tabby.mixin.ClientLevelAccessor;
import dev.tabby.mixin.LevelRendererAccessor;
import dev.tabby.mixin.ParticleEngineAccessor;
import dev.tabby.compat.SimpleVoiceChatCompatibility;
import dev.tabby.compat.MeteorTabIsolation;
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
import net.minecraft.network.ConnectionProtocol;
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
 * Coordinates world transitions and exposes the stable API used by mixins and UI.
 */
public final class TabManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    private static final ThreadLocal<ArrayDeque<Integer>> CLIENT_LEVEL_TRANSITIONS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Integer>> CLIENT_LOGIN_HANDLERS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Integer> CLIENT_DISCONNECT_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final boolean SMOKE_TEST_ENABLED = "1".equals(System.getenv("TABBY_SMOKE_TEST"));

    private static Minecraft client;
    private static int mainTabId = -1;
    private static int smokeTestTicks;
    private static int blockedSingleplayerWorldStartCount;
    private static final List<String> RESTORE_QUEUE = new ArrayList<>();
    private static int restoreTickDelay;
    private static int memoryRefreshTicks;

    private TabManager() {
    }

    static Minecraft clientInstance() {
        return client;
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
        mainTabId = initial.id();
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

        TabCrashGuard.tick(minecraft);
        TabReconnectManager.tick(minecraft);
        tickRestoredServerAddresses(minecraft);
        TabConnectionManager.tickBackgroundConnections();
        TabDamageNotificationManager.tick(minecraft);
        if (TabbyConfig.showHudInfo() || TabbyConfig.showTabMemoryLabel()) {
            if (++memoryRefreshTicks >= 40) {
                memoryRefreshTicks = 0;
                TabMemoryUsage.refresh(TabRegistry.values());
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
                TabLifecycleManager.isSingleplayerTab(context),
                hasInactiveSingleplayerWorld()
            );
        }
    }

    public static void endClientDisconnect() {
        endClientDisconnect(true);
    }

    /**
     * Releases the disconnect guard even if vanilla or another mod throws during cleanup.
     * A failed disconnect must not publish partially-cleared Minecraft globals to a tab.
     */
    public static void endClientDisconnect(boolean completed) {
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
        if (client == null || !completed) {
            if (!completed) {
                LOGGER.warn("Vanilla disconnect failed; discarded the temporary client state without capturing it");
            }
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

        int ownerId = ConnectionOwnershipRegistry.ownerIdFor(listener);
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
        int ownerId = handlers.isEmpty() ? ConnectionOwnershipRegistry.ownerIdFor(listener) : handlers.pop();
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
            boolean loginCompleted = context.level != null
                && context.player != null
                && context.clientConnection == listener
                && context.networkConnection != null
                && context.networkConnection.isConnected();
            if (loginCompleted) {
                context.connectedAtLeastOnce = true;
                context.lastBackgroundFailure = null;
                context.deferredBackgroundKnockback = false;
                TabReconnectManager.onLoginSucceeded(ownerId);
            }
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
        int listenerOwnerId = ConnectionOwnershipRegistry.ownerIdFor(incomingListener);
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

    /**
     * Clears a level-transition marker when Minecraft or another mod throws before
     * the incoming level is fully installed. Nested transitions for the same tab remain guarded.
     */
    public static void abortClientLevelTransition(ClientLevel failedLevel) {
        if (client == null) {
            return;
        }

        ArrayDeque<Integer> transitions = CLIENT_LEVEL_TRANSITIONS.get();
        if (transitions.isEmpty()) {
            CLIENT_LEVEL_TRANSITIONS.remove();
            return;
        }

        int ownerId = transitions.pop();
        boolean sameTabStillTransitioning = transitions.contains(ownerId);
        if (transitions.isEmpty()) {
            CLIENT_LEVEL_TRANSITIONS.remove();
        }

        TabContext context = TabRegistry.get(ownerId);
        if (context != null && !sameTabStillTransitioning) {
            context.worldTransitioning = false;
            context.worldTransitionTicks = 0;
        }
        LOGGER.warn(
            "Aborted ClientLevel installation for tab {} after an exception (incomingLevel={})",
            ownerId,
            failedLevel == null ? "null" : System.identityHashCode(failedLevel)
        );
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
        int listenerOwnerId = ConnectionOwnershipRegistry.ownerIdFor(listener);
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

    static void restoreClientGlobals(TabContext context) {
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
        TabLifecycleManager.createTab();
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
        TabLifecycleManager.togglePinned(tabId);
    }

    public static void moveTab(int tabId, int targetIndex) {
        TabLifecycleManager.moveTab(tabId, targetIndex);
    }

    public static boolean disconnectActiveTabFromPauseScreen() {
        return TabConnectionManager.disconnectActiveTabFromPauseScreen();
    }

    public static boolean handleListenerDisconnect(
        ClientCommonPacketListenerImpl listener,
        DisconnectionDetails details
    ) {
        return TabConnectionManager.handleListenerDisconnect(listener, details);
    }

    public static void closeTab(int tabId) {
        TabLifecycleManager.closeTab(tabId);
    }

    public static void shutdown() {
        Minecraft shuttingDownClient = client;
        if (shuttingDownClient == null) {
            return;
        }

        SimpleVoiceChatCompatibility.onActiveTabDisconnected();
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

        for (TabContext context : TabRegistry.values()) {
            TabLifecycleManager.disposeTab(context, context.id() != TabRegistry.activeId());
        }

        TabRegistry.clear();
        TabMemoryUsage.clear();
        TabDamageNotificationManager.clear();
        TabUnreadMessageManager.clear();
        TabReconnectManager.clear();
        TabCrashGuard.clear();
        ConnectionOwnershipRegistry.clear();
        TabContextExecutor.clearCurrentThread();
        TabRegistry.setActiveId(-1);
        client = null;
    }

    public static boolean hasInactiveSingleplayerWorld() {
        return TabLifecycleManager.hasInactiveSingleplayerWorld();
    }

    public static boolean shouldBlockSingleplayerWorldStart() {
        return TabLifecycleManager.shouldBlockSingleplayerWorldStart();
    }

    public static boolean shouldPreserveIntegratedServerDuringDisconnect() {
        return TabLifecycleManager.shouldPreserveIntegratedServerDuringDisconnect();
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
        List<Integer> ids = TabRegistry.ids();
        if (index >= 0 && index < ids.size()) {
            switchTo(ids.get(index));
        }
    }

    public static void switchToNext() {
        if (TabRegistry.size() < 2) {
            return;
        }

        List<Integer> ids = TabRegistry.ids();
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
            MeteorTabIsolation.beforeSwitch(previous == null ? -1 : previous.id(), target.id());
            KeyMapping.releaseAll();
            if (previous != null) {
                previous.refreshMovementInputAfterKeyRelease();
            }
            target.refreshMovementInputAfterKeyRelease();
            // Drop velocity impulses delivered while this tab's player physics was paused.
            TabMotionTracker.beforeActivate(target);
            client.getSoundManager().stop();
            client.mouseHandler.setIgnoreFirstMove();

            if (previous != null) {
                TabRendererCompatibility.suspendVoxyRenderer(previous.levelRenderer, previous.id());
            }
            target.applyTo(client, false);
            TabRegistry.setActiveId(tabId);
            TabUnreadMessageManager.markRead(tabId);
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
            target.refreshMovementInputAfterKeyRelease();
            client.mouseHandler.setIgnoreFirstMove();
            target.captureFrom(client);
            MeteorTabIsolation.afterSwitch(tabId);
            SimpleVoiceChatCompatibility.onActiveTabChanged(client);
            LOGGER.debug("Switched active tab to {} ({})", target.id(), target.title());
        } catch (RuntimeException | Error exception) {
            LOGGER.debug("Failed to switch to tab {}", tabId, exception);
            if (previous != null) {
                TabRegistry.setActiveId(previous.id());
                previous.applyTo(client, false);
                TabRendererCompatibility.resumeVoxyRenderer(previous.levelRenderer, previous.level, previous.id());
                client.setScreen(previous.screen);
                KeyMapping.releaseAll();
                previous.refreshMovementInputAfterKeyRelease();
                client.mouseHandler.setIgnoreFirstMove();
                try {
                    MeteorTabIsolation.abortSwitch(previous.id());
                } catch (RuntimeException | Error restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            throw exception;
        }
    }

    public static Runnable wrapPacketTask(PacketListener listener, Runnable task) {
        if (client == null) {
            return task;
        }

        int ownerId = ConnectionOwnershipRegistry.ownerIdFor(listener);
        if (ownerId < 0) {
            return task;
        }

        // Capture the owner when the task is queued, not the active tab at arrival.
        // The active tab may change before Minecraft executes the queued packet task.
        return () -> runIn(ownerId, task);
    }

    public static void runIn(int tabId, Runnable task) {
        int selectedTab = TabRegistry.activeId();
        TabContextExecutor.runIn(
            client,
            selectedTab,
            TabRegistry.view(),
            tabId,
            () -> MeteorTabIsolation.runInTabContext(tabId, selectedTab, task)
        );
    }

    /**
     * Called before vanilla's ConnectScreen disconnects the current connection.
     * Returning true prevents a duplicate attempt from disrupting the existing tab.
     */
    public static void disconnectCurrentTabBeforeConnecting(Minecraft minecraft) {
        TabConnectionManager.disconnectCurrentTabBeforeConnecting(minecraft);
    }

    public static boolean handleConnectionAttempt(ServerAddress address, ServerData serverData) {
        return TabConnectionManager.handleConnectionAttempt(address, serverData);
    }

    public static boolean onScreenChangeRequested(Screen screen) {
        return TabConnectionManager.onScreenChangeRequested(screen);
    }

    public static void onConnectionDisconnected(Connection connection) {
        TabConnectionManager.onConnectionDisconnected(connection);
    }

    public static void onConnectionCreated(Connection connection) {
        TabConnectionManager.onConnectionCreated(connection);
    }

    public static void onListenerAssigned(Connection connection, PacketListener listener) {
        TabConnectionManager.onListenerAssigned(connection, listener);
    }

    public static void onPlayerDamage(ClientPacketListener listener, int entityId) {
        TabDamageNotificationManager.onPlayerDamage(listener, entityId);
    }

    public static void onPlayerVelocity(ClientPacketListener listener, int entityId) {
        TabMotionTracker.onPlayerVelocity(listener, entityId);
    }

    public static void onPlayerExplosion(ClientPacketListener listener, double x, double y, double z) {
        TabMotionTracker.onPlayerExplosion(listener, x, y, z);
    }

    public static void onBackgroundChatMessage(Component message) {
        TabUnreadMessageManager.onChatMessage(message);
    }

    public static boolean shouldSuppressOutOfPhasePlayPacket(Packet<?> packet, ConnectionProtocol phase) {
        return TabConnectionManager.shouldSuppressOutOfPhasePlayPacket(packet, phase);
    }


    /**
     * Returns whether Minecraft's current global fields are installed for the given tab.
     * Context snapshots must never capture globals belonging to another tab.
     */
    public static boolean isContextInstalled(int tabId) {
        return TabContextExecutor.isContextInstalled(client != null, TabRegistry.activeId(), tabId);
    }

    /**
     * Baritone owns one process-global primary instance. Its callbacks and forced input
     * must only run while the original main-tab context is installed.
     */
    public static boolean isBaritoneContextAllowed() {
        return client == null || mainTabId < 0 || currentContextId() == mainTabId;
    }

    /**
     * Run Baritone's process tick against the original main-tab context, even while
     * another connected tab is visible. A hidden main tab is ticked only when both it
     * and the visible tab have stable player/world state; this prevents an OUT event
     * generated by a menu tab from cancelling the main tab's path.
     */
    public static void runBaritoneTickInMain(Runnable task) {
        if (task == null) {
            return;
        }
        if (client == null || mainTabId < 0) {
            task.run();
            return;
        }

        TabContext main = TabRegistry.get(mainTabId);
        if (main == null) {
            return;
        }

        if (TabRegistry.activeId() == mainTabId) {
            // When main is visible, preserve vanilla's normal IN/OUT tick semantics.
            if (main.worldTransitioning || main.disconnecting) {
                return;
            }
        } else {
            TabContext visible = TabRegistry.get(TabRegistry.activeId());
            if (!hasStableBaritoneWorld(main)
                || visible == null
                || visible.worldTransitioning
                || visible.disconnecting) {
                return;
            }
        }

        runIn(mainTabId, task);
    }

    public static boolean isBaritoneMainWorldStable() {
        return hasStableBaritoneWorld(TabRegistry.get(mainTabId));
    }

    private static boolean hasStableBaritoneWorld(TabContext context) {
        return context != null
            && context.level != null
            && context.player != null
            && !context.worldTransitioning
            && !context.disconnecting
            && (context.networkConnection == null || context.networkConnection.isConnected());
    }

    static int currentContextId() {
        return TabContextExecutor.currentContextId(TabRegistry.activeId());
    }

    /** Returns the tab whose Minecraft fields are currently installed on this thread. */
    public static int executionTabId() {
        return currentContextId();
    }

    public static int activeId() {
        return TabRegistry.activeId();
    }

    public static List<TabContext> contexts() {
        return TabRegistry.values();
    }
}
