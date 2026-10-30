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
    private static final ThreadLocal<ArrayDeque<Integer>> CLIENT_LEVEL_TRANSITIONS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Integer>> CLIENT_LOGIN_HANDLERS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Integer> CLIENT_DISCONNECT_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final boolean SMOKE_TEST_ENABLED = "1".equals(System.getenv("TABBY_SMOKE_TEST"));

    private static Minecraft client;
    private static volatile int activeId = -1;
    private static int nextId;
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
        TabContext initial = TabContext.capture(client, nextId++, "Main Menu");
        TABS.put(initial.id(), initial);
        activeId = initial.id();

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
        // That packet handler runs under runIn(tabId), so activeId still identifies the
        // visible tab, not the context temporarily installed for the packet. Running the
        // background-connection loop here would tick the visible tab against the temporary
        // context and can clear the incoming world's gameMode/player state. Only perform
        // Tabby maintenance from the outer, ordinary client tick.
        Integer executionId = EXECUTION_CONTEXT.get();
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
                TabMemoryUsage.refresh(List.copyOf(TABS.values()));
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

    /**
     * Only the selected tab's LevelRenderer may enter the world-rendering pipeline.
     */
    public static boolean shouldRenderRenderer(LevelRenderer renderer) {
        if (renderer == null || client == null || activeId < 0) {
            return true;
        }
        if (isBackgroundContext()) {
            return false;
        }

        TabContext active = TABS.get(activeId);
        return active == null || active.levelRenderer == renderer;
    }

    /**
     * Voxy attaches one GPU-heavy render system to every LevelRenderer. Only the
     * selected tab may own that render system; inactive tab renderers keep their
     * world identifier and recreate the Voxy renderer when selected again.
     */
    public static boolean shouldDeferVoxyRendererCreation(LevelRenderer renderer) {
        if (client == null || renderer == null) {
            return false;
        }

        if (isBackgroundContext()) {
            return true;
        }

        TabContext active = TABS.get(activeId);
        return active != null && active.levelRenderer != null && active.levelRenderer != renderer;
    }

    private static void suspendVoxyRenderer(LevelRenderer renderer, int tabId) {
        if (renderer == null) {
            return;
        }

        try {
            java.lang.reflect.Method getRenderer = renderer.getClass().getMethod("voxy$getRenderSystem");
            Object voxyRenderer = getRenderer.invoke(renderer);
            if (voxyRenderer == null) {
                return;
            }

            renderer.getClass().getMethod("voxy$shutdownRenderer").invoke(renderer);
            LOGGER.info("Suspended Voxy render system for background tab {}", tabId);
        } catch (NoSuchMethodException ignored) {
            // Voxy is optional; its methods exist only when its runtime mixin is applied.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("Could not suspend Voxy render system for tab {}", tabId, exception);
        }
    }

    private static void resumeVoxyRenderer(LevelRenderer renderer, ClientLevel level, int tabId) {
        if (renderer == null || level == null) {
            return;
        }

        try {
            java.lang.reflect.Method getRenderer = renderer.getClass().getMethod("voxy$getRenderSystem");
            if (getRenderer.invoke(renderer) != null) {
                return;
            }

            renderer.getClass().getMethod("voxy$createRenderer").invoke(renderer);
            LOGGER.info("Activated Voxy render system for active tab {}", tabId);
        } catch (NoSuchMethodException ignored) {
            // Voxy is optional; its methods exist only when its runtime mixin is applied.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("Could not activate Voxy render system for tab {}", tabId, exception);
        }
    }

    public static TabContext activeContext() {
        return TABS.get(activeId);
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
        if (client == null || isClientDisconnectInProgress() || isClientLevelTransitioning() || isClientLoginInProgress()) {
            // Minecraft.setLevel() and ClientPacketListener.handleLogin() contain nested
            // screen ticks. Do not capture intentionally transient globals until the full
            // login packet handler has created and installed its player.
            return;
        }

        // Vanilla intentionally clears client.level/player while it disconnects the
        // previous world and loads a replacement. Do not capture or "repair" that
        // temporary state: the old listener may already have cleared its level.
        if (EXECUTION_CONTEXT.get() == null) {
            TabContext active = TABS.get(activeId);
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

        TabContext context = TABS.get(currentContextId());
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
            TabContext context = TABS.get(currentContextId());
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

        TabContext context = TABS.get(currentContextId());
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

        TabContext context = TABS.get(currentContextId());
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

        TabContext context = TABS.get(ownerId);
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
        TabContext context = TABS.get(ownerId);
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

        TabContext context = TABS.get(ownerId);
        if (context == null) {
            LOGGER.warn("Installed a ClientLevel for unknown tab {}; active tab is {}", ownerId, activeId);
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
        if (EXECUTION_CONTEXT.get() != null) {
            return false;
        }

        TabContext active = TABS.get(activeId);
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

        for (TabContext other : TABS.values()) {
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
        resumeVoxyRenderer(context.levelRenderer, context.level, context.id());
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
        if (client == null || NetworkSmokeTest.isEnabled() || CompatibilitySmokeTest.isEnabled() || RESTORE_QUEUE.isEmpty()) {
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
        moveTab(tabId, context.pinned ? 0 : TABS.size());
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
        TabMemoryUsage.remove(id);

        synchronized (CONNECTION_OWNERS) {
            CONNECTION_OWNERS.entrySet().removeIf(entry -> entry.getValue() == id);
        }
        synchronized (LISTENER_OWNERS) {
            LISTENER_OWNERS.entrySet().removeIf(entry -> entry.getValue() == id);
        }

        stopIntegratedServerForTab(context);
        suspendVoxyRenderer(context.levelRenderer, id);

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

        boolean anotherTabOwnsLocalWorld = TABS.values().stream()
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
        TabMemoryUsage.clear();
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

        TabContext active = TABS.get(activeId);
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

        boolean pauseMenuRequested = client.screen instanceof PauseScreen;
        TabContext previous = TABS.get(activeId);
        TabContext target = TABS.get(tabId);
        if (previous != null) {
            previous.captureFrom(client);
        }

        try {
            KeyMapping.releaseAll();
            client.getSoundManager().stop();
            client.mouseHandler.setIgnoreFirstMove();

            if (previous != null) {
                suspendVoxyRenderer(previous.levelRenderer, previous.id());
            }
            target.applyTo(client, false);
            activeId = tabId;
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
            resumeVoxyRenderer(target.levelRenderer, target.level, target.id());
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
                activeId = previous.id();
                previous.applyTo(client, false);
                resumeVoxyRenderer(previous.levelRenderer, previous.level, previous.id());
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
            // Even tasks for the visible tab need an execution marker. Minecraft may call
            // setLevel() from a login handler, and setLevel() performs a nested client tick.
            // Without this marker, Tabby would run its background-connection loop inside
            // that nested tick while the active tab is temporarily between level states.
            Integer previousExecutionId = EXECUTION_CONTEXT.get();
            if (previousExecutionId != null) {
                task.run();
                return;
            }

            EXECUTION_CONTEXT.set(tabId);
            try {
                task.run();
            } catch (RuntimeException | Error exception) {
                LOGGER.debug("Packet task failed in active tab {}", tabId, exception);
                throw exception;
            } finally {
                EXECUTION_CONTEXT.remove();
            }
            return;
        }

        TabContext previous = TABS.get(previousId);
        TabContext target = TABS.get(tabId);
        if (target == null) {
            LOGGER.debug("Ignoring task for unknown tab {}", tabId);
            return;
        }

        Integer previousExecutionId = EXECUTION_CONTEXT.get();
        TabContext previousState = previous;
        if (previous != null) {
            if (previousExecutionId == null) {
                // At the outermost packet boundary the visible context is stable and can
                // be committed to its persistent snapshot.
                previous.captureFrom(client);
            } else {
                // A nested runIn can happen from Minecraft's forced tick while an outer
                // login handler is halfway through setLevel/createPlayer. Save the exact
                // transient globals for restoration, but never publish them into the
                // persistent tab snapshot (for example gameMode == null during login).
                previousState = TabContext.capture(client, previousId, previous.title());
            }
        }

        try {
            EXECUTION_CONTEXT.set(tabId);
            // Never swap the global Screen reference while executing a background packet.
            // Screen changes are intercepted by MinecraftMixin and stored on the owning tab.
            target.applyTo(client, false);
            task.run();
        } catch (RuntimeException | Error exception) {
            LOGGER.debug("Packet task failed in tab {}", tabId, exception);
            throw exception;
        } finally {
            try {
                target.captureFrom(client);
            } finally {
                try {
                    if (previousState != null) {
                        previousState.applyTo(client, false);
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

        TabContext context = TABS.get(currentContextId());
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

            if (context.localServer && context.level != null && !context.worldTransitioning) {
                LOGGER.warn(
                    "Disconnect cleanup reached integrated-world tab {} while active tab {} owns the client (localServer={}, level={}, connectionMatch={}, listenerMatch={})",
                    context.id(),
                    activeId,
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
        return client != null && currentContextId() == tabId;
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
