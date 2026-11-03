package dev.tabby.compat;

import dev.tabby.tab.TabManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;

/**
 * Keeps Simple Voice Chat's one global voice session aligned with Tabby's selected tab.
 *
 * SVC intentionally has a client-global ClientManager, microphone thread, UDP connection
 * and audio channel map. Tabby therefore moves that one session between selected tabs
 * instead of pretending that several independent SVC sessions can coexist in one client.
 */
public final class SimpleVoiceChatCompatibility {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    private static final String MOD_ID = "voicechat";
    private static final String MANAGER_CLASS =
        "de.maxhenkel.voicechat.voice.client.ClientManager";

    // Accessed only from Minecraft's client thread.
    private static Connection managedConnection;
    private static boolean warnedAboutMissingBridge;

    private SimpleVoiceChatCompatibility() {
    }

    public static boolean isAvailable() {
        return manager() != null;
    }

    public static boolean hasActiveClientSession() {
        SimpleVoiceChatManagerAccess manager = manager();
        return manager != null && readClientVoicechat(manager) != null;
    }

    /**
     * Re-authenticates SVC against the selected tab, or disconnects it for a menu tab.
     */
    public static void onActiveTabChanged(Minecraft client) {
        if (client == null) {
            return;
        }

        SimpleVoiceChatManagerAccess manager = manager();
        if (manager == null) {
            return;
        }

        ClientPacketListener listener = client.getConnection();
        Connection connection = listener == null ? null : listener.getConnection();
        if (client.level == null || client.player == null
            || connection == null || !connection.isConnected()) {
            disconnect(manager);
            return;
        }

        if (managedConnection == connection) {
            return;
        }

        try {
            manager.tabby$invokeOnJoinWorld();
            managedConnection = connection;
            LOGGER.info("Simple Voice Chat session rebound to active Tabby tab {}",
                TabManager.activeId());
        } catch (RuntimeException | LinkageError exception) {
            managedConnection = null;
            LOGGER.warn("Failed to rebind Simple Voice Chat to the active Tabby tab", exception);
        }
    }

    /**
     * Called when the visible tab is disconnected or the Minecraft client is shutting down.
     */
    public static void onActiveTabDisconnected() {
        SimpleVoiceChatManagerAccess manager = manager();
        if (manager != null) {
            disconnect(manager);
        } else {
            managedConnection = null;
        }
    }

    /**
     * CI regression test: force SVC's lifecycle entry points under a hidden tab context.
     * Neither callback is allowed to replace or destroy the one client-global SVC session.
     */
    public static boolean verifyBackgroundCallbacksAreSuppressed(int backgroundTabId) {
        SimpleVoiceChatManagerAccess manager = manager();
        if (manager == null || backgroundTabId == TabManager.activeId()) {
            return false;
        }

        Object initialClient = readClientVoicechat(manager);
        TabManager.runIn(backgroundTabId, manager::tabby$invokeOnJoinWorld);
        Object afterJoin = readClientVoicechat(manager);
        if (afterJoin != initialClient) {
            return false;
        }

        TabManager.runIn(backgroundTabId, manager::tabby$invokeOnDisconnect);
        return readClientVoicechat(manager) == initialClient;
    }

    private static Object readClientVoicechat(SimpleVoiceChatManagerAccess manager) {
        try {
            java.lang.reflect.Field clientField = manager.getClass().getDeclaredField("client");
            clientField.setAccessible(true);
            return clientField.get(manager);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("Could not inspect Simple Voice Chat's active client session", exception);
            return null;
        }
    }

    private static void disconnect(SimpleVoiceChatManagerAccess manager) {
        try {
            manager.tabby$invokeOnDisconnect();
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.warn("Failed to disconnect Simple Voice Chat while leaving a Tabby tab", exception);
        } finally {
            managedConnection = null;
        }
    }

    private static SimpleVoiceChatManagerAccess manager() {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            return null;
        }

        try {
            Class<?> managerType = Class.forName(
                MANAGER_CLASS,
                false,
                SimpleVoiceChatCompatibility.class.getClassLoader()
            );
            Object instance = managerType.getMethod("instance").invoke(null);
            if (instance instanceof SimpleVoiceChatManagerAccess access) {
                warnedAboutMissingBridge = false;
                return access;
            }
            if (!warnedAboutMissingBridge) {
                warnedAboutMissingBridge = true;
                LOGGER.warn(
                    "Simple Voice Chat is installed, but its optional Tabby bridge was not applied; voice chat will retain vanilla lifecycle behavior"
                );
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            if (!warnedAboutMissingBridge) {
                warnedAboutMissingBridge = true;
                LOGGER.warn("Could not access the optional Simple Voice Chat client manager", exception);
            }
        }
        return null;
    }
}
