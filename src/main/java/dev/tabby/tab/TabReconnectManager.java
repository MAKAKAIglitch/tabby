package dev.tabby.tab;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import dev.tabby.mixin.MinecraftAccessor;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Retries unexpected remote-server disconnects independently for each tab.
 * Manual disconnects, tab closure and integrated single-player sessions are excluded.
 */
final class TabReconnectManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    static final int MAX_ATTEMPTS = 5;
    static final int RETRY_DELAY_TICKS = 100;
    private static final int ATTEMPT_TIMEOUT_TICKS = 400;

    private static final Map<Integer, RetryState> RETRIES = new HashMap<>();
    private static final Set<Integer> AUTOMATIC_LAUNCHES = new HashSet<>();
    private static long clientTick;

    private TabReconnectManager() {
    }

    static boolean isEligible(TabContext context, boolean intentionalDisconnect) {
        return TabbyConfig.autoReconnect()
            && !intentionalDisconnect
            && context != null
            && context.connectedAtLeastOnce
            && !context.localServer
            && context.serverData != null
            && context.serverData.ip != null
            && !context.serverData.ip.isBlank();
    }

    static void onUnexpectedDisconnect(TabContext context, Connection connection, Component reason) {
        Minecraft client = TabManager.clientInstance();
        if (client == null || context == null || connection == null) {
            return;
        }

        int tabId = context.id();
        Runnable task = () -> scheduleOnClientThread(tabId, connection, reason);
        if (client.isSameThread()) {
            task.run();
        } else {
            client.execute(task);
        }
    }

    private static void scheduleOnClientThread(int tabId, Connection connection, Component reason) {
        TabContext context = TabRegistry.get(tabId);
        boolean intentional = context == null
            || context.disconnecting
            || TabManager.isClientDisconnectInProgress();
        RetryState state = RETRIES.get(tabId);
        boolean connectionMatches = context != null && (
            context.networkConnection == connection
                || context.pendingConnection == connection
                || context.replacingConnection == connection
                || context.clientConnection != null
                    && context.clientConnection.getConnection() == connection
        );
        boolean eligible = isEligible(context, intentional);
        if (!eligible || connection.isMemoryConnection() || !connectionMatches
            || context.connecting && (state == null || !state.inFlight)) {
            LOGGER.warn(
                "Skipped reconnect scheduling for tab {} (eligible={}, intentional={}, memory={}, ownerMatch={}, connectedOnce={}, localServer={}, serverData={}, disconnecting={}, connecting={}, retryInFlight={})",
                tabId,
                eligible,
                intentional,
                connection.isMemoryConnection(),
                connectionMatches,
                context != null && context.connectedAtLeastOnce,
                context != null && context.localServer,
                context == null ? null : context.serverData == null ? "null" : context.serverData.ip,
                context != null && context.disconnecting,
                context != null && context.connecting,
                state != null && state.inFlight
            );
            return;
        }

        LOGGER.info("Accepted unexpected disconnect for tab {} ({}); scheduling recovery",
            tabId, context.serverData.ip);
        state = RETRIES.get(tabId);
        if (state == null) {
            state = new RetryState(context.serverData, reason == null ? "Connection lost" : reason.getString());
            RETRIES.put(tabId, state);
            state.nextAttemptTick = clientTick + RETRY_DELAY_TICKS;
            LOGGER.warn(
                "Tabby will reconnect tab {} to {} in {} ticks",
                tabId,
                context.serverData.ip,
                RETRY_DELAY_TICKS
            );
            return;
        }

        if (state.inFlight) {
            state.inFlight = false;
            scheduleNext(state, tabId, reason == null ? "Reconnect attempt failed" : reason.getString());
        }
    }

    static void onRetryFailed(int tabId) {
        RetryState state = RETRIES.get(tabId);
        if (state == null || !state.inFlight) {
            return;
        }
        state.inFlight = false;
        scheduleNext(state, tabId, "Reconnect attempt did not reach the play session");
    }

    private static void scheduleNext(RetryState state, int tabId, String reason) {
        if (state.attempts >= MAX_ATTEMPTS) {
            RETRIES.remove(tabId);
            LOGGER.warn("Automatic reconnect exhausted for tab {} after {} attempts: {}", tabId, state.attempts, reason);
            showToast("tabby.toast.reconnect_failed", tabId);
            return;
        }

        state.nextAttemptTick = clientTick + RETRY_DELAY_TICKS;
        LOGGER.warn(
            "Reconnect attempt {} for tab {} failed; retrying in {} ticks ({})",
            state.attempts,
            tabId,
            RETRY_DELAY_TICKS,
            reason
        );
    }

    static void tick(Minecraft client) {
        if (client == null) {
            return;
        }
        clientTick++;
        if (!TabbyConfig.autoReconnect()) {
            RETRIES.clear();
            return;
        }

        for (Map.Entry<Integer, RetryState> entry : Map.copyOf(RETRIES).entrySet()) {
            int tabId = entry.getKey();
            RetryState state = entry.getValue();
            TabContext context = TabRegistry.get(tabId);
            if (context == null || context.localServer || context.disconnecting) {
                RETRIES.remove(tabId);
                continue;
            }

            if (state.inFlight) {
                boolean loginComplete = context.level != null
                    && context.player != null
                    && context.clientConnection != null
                    && context.networkConnection != null
                    && context.networkConnection.isConnected();
                if (clientTick >= state.attemptTimeoutTick && !loginComplete) {
                    state.inFlight = false;
                    abortAttempt(client, context);
                    scheduleNext(state, tabId, "Login timed out");
                }
                continue;
            }

            if (context.connecting || context.networkConnection != null && context.networkConnection.isConnected()
                || context.pendingConnection != null && context.pendingConnection.isConnected()) {
                continue;
            }
            if (clientTick < state.nextAttemptTick) {
                continue;
            }
            if (state.attempts >= MAX_ATTEMPTS) {
                RETRIES.remove(tabId);
                showToast("tabby.toast.reconnect_failed", tabId);
                continue;
            }

            launchAttempt(client, context, state);
        }
    }

    private static void launchAttempt(Minecraft client, TabContext context, RetryState state) {
        int tabId = context.id();
        state.attempts++;
        state.inFlight = true;
        state.attemptTimeoutTick = clientTick + ATTEMPT_TIMEOUT_TICKS;
        context.screen = new TitleScreen();
        context.connecting = false;
        AUTOMATIC_LAUNCHES.add(tabId);

        LOGGER.info(
            "Automatically reconnecting tab {} to {} (attempt {}/{})",
            tabId,
            state.serverData.ip,
            state.attempts,
            MAX_ATTEMPTS
        );
        try {
            TabManager.runIn(tabId, () -> ConnectScreen.startConnecting(
                context.screen,
                client,
                ServerAddress.parseString(state.serverData.ip),
                state.serverData,
                false,
                null
            ));
            TabConnectionManager.restoreVisibleSharedRenderState(tabId);
        } catch (RuntimeException exception) {
            state.inFlight = false;
            LOGGER.warn("Could not start automatic reconnect for tab {}", tabId, exception);
            abortAttempt(client, context);
            scheduleNext(state, tabId, exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage());
        } finally {
            AUTOMATIC_LAUNCHES.remove(tabId);
        }
    }

    private static void abortAttempt(Minecraft client, TabContext context) {
        int tabId = context.id();
        Connection pending = context.pendingConnection;
        Connection network = context.networkConnection;

        try {
            TabManager.runIn(tabId, () -> {
                context.connecting = false;
                context.pendingConnection = null;
                context.networkConnection = null;
                context.clientConnection = null;
                context.packetListener = null;
                context.replacingConnection = null;
                context.level = null;
                context.player = null;
                context.gameMode = null;
                context.worldTransitioning = false;
                context.worldTransitionTicks = 0;
                context.screen = new TitleScreen();
                context.localServer = false;
                context.gui = new net.minecraft.client.gui.Gui(client);
                context.hitResult = null;
                context.crosshairPickEntity = null;
                context.cameraEntity = null;
                client.level = null;
                client.player = null;
                client.gameMode = null;
                client.gui = context.gui;
                client.hitResult = null;
                client.crosshairPickEntity = null;
                client.cameraEntity = null;
                MinecraftAccessor accessor = (MinecraftAccessor) client;
                accessor.tabby$setPendingConnection(null);
                accessor.tabby$setLocalServer(false);
                client.entityRenderDispatcher.setLevel(null);
                if (context.levelRenderer != null) {
                    context.levelRenderer.setLevel(null);
                }
                if (pending != null && (pending.isConnected() || pending.isConnecting())) {
                    pending.disconnect(Component.literal("Tabby reconnect attempt timed out"));
                }
                if (network != null && network != pending
                    && (network.isConnected() || network.isConnecting())) {
                    network.disconnect(Component.literal("Tabby reconnect attempt timed out"));
                }
            });
        } finally {
            if (tabId == TabRegistry.activeId()) {
                TabManager.restoreClientGlobals(context);
                client.setScreen(context.screen);
            } else {
                TabConnectionManager.restoreVisibleSharedRenderState(tabId);
            }
        }
    }

    static boolean isAutomaticLaunch(int tabId) {
        return AUTOMATIC_LAUNCHES.contains(tabId);
    }

    static boolean isInFlight(int tabId) {
        RetryState state = RETRIES.get(tabId);
        return state != null && state.inFlight;
    }

    static boolean isScheduled(int tabId) {
        return RETRIES.containsKey(tabId);
    }

    static String statusLabel(int tabId) {
        return RETRIES.containsKey(tabId) ? "↻" : "";
    }

    static int attempts(int tabId) {
        RetryState state = RETRIES.get(tabId);
        return state == null ? 0 : state.attempts;
    }

    static void onLoginSucceeded(int tabId) {
        RetryState state = RETRIES.remove(tabId);
        if (state != null) {
            LOGGER.info("Automatic reconnect succeeded for tab {} after {} attempt(s)", tabId, state.attempts);
            showToast("tabby.toast.reconnect_success", tabId);
        }
    }

    static void cancel(int tabId) {
        RETRIES.remove(tabId);
    }

    static void removeTab(int tabId) {
        RETRIES.remove(tabId);
        AUTOMATIC_LAUNCHES.remove(tabId);
    }

    static void clear() {
        RETRIES.clear();
        AUTOMATIC_LAUNCHES.clear();
        clientTick = 0L;
    }

    private static void showToast(String key, int tabId) {
        Minecraft client = TabManager.clientInstance();
        TabContext context = TabRegistry.get(tabId);
        if (client == null || context == null) {
            return;
        }
        SystemToast.add(
            client.getToastManager(),
            SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
            Component.literal("Tabby"),
            Component.translatable(key, context.title())
        );
    }

    private static final class RetryState {
        private final ServerData serverData;
        private String lastReason;
        private int attempts;
        private long nextAttemptTick;
        private long attemptTimeoutTick;
        private boolean inFlight;

        private RetryState(ServerData serverData, String reason) {
            this.serverData = serverData;
            this.lastReason = reason;
        }
    }
}
