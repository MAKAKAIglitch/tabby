package dev.tabby.tab;

import dev.tabby.compat.SimpleVoiceChatCompatibility;

import dev.tabby.mixin.MinecraftAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.server.level.ParticleStatus;
import java.lang.reflect.Proxy;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Short world-render and context-switch smoke test used with one optional compatibility mod.
 * The test is enabled only by TABBY_COMPAT_SMOKE_TEST=1.
 */
public final class CompatibilitySmokeTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    private static final boolean ENABLED = "1".equals(System.getenv("TABBY_COMPAT_SMOKE_TEST"));
    private static final String MOD_ID = environmentValue("TABBY_COMPAT_MOD", "");
    private static final int JOIN_TIMEOUT_TICKS = 1200;
    private static final int SWITCH_INTERVAL_TICKS = 40;
    private static final int REQUIRED_SWITCH_TICKS = 240;

    private enum Phase {
        CONNECT,
        WAIT_FOR_WORLD,
        WAIT_FOR_SECOND_WORLD,
        SWITCHING
    }

    private static Phase phase = Phase.CONNECT;
    private static int phaseTicks;
    private static int switchTicks;
    private static boolean modVerified;
    private static int baritoneMainTickCount;
    private static int baritoneForeignTickCount;

    private CompatibilitySmokeTest() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void tick(Minecraft client) {
        if (!ENABLED || !client.isGameLoadFinished()) {
            return;
        }

        switch (phase) {
            case CONNECT -> beginConnection(client);
            case WAIT_FOR_WORLD -> waitForWorld(client);
            case WAIT_FOR_SECOND_WORLD -> waitForSecondWorld(client);
            case SWITCHING -> exerciseWorldSwitching(client);
        }
    }

    private static void beginConnection(Minecraft client) {
        if (!modVerified) {
            verifyConnectionOwnershipCleanup();
            verifyExpectedModLoaded();
            modVerified = true;
        }
        if (++phaseTicks < 20) {
            return;
        }

        require(TabManager.activeId() == 0, "Compatibility test expected the initial tab to be active");
        // Headless compatibility runs can tick entities before particle atlases finish reloading.
        // Particles are not part of this renderer/context-switch smoke test.
        client.options.particles().set(ParticleStatus.MINIMAL);
        String address = environmentValue("TABBY_SERVER_A", "127.0.0.1:25565");
        ServerData serverData = new ServerData("Tabby compatibility server", address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(
            client.screen,
            client,
            ServerAddress.parseString(address),
            serverData,
            false,
            null
        );
        LOGGER.info("Compatibility test for {}: connecting to {}", MOD_ID, address);
        phase = Phase.WAIT_FOR_WORLD;
        phaseTicks = 0;
    }

    private static void waitForWorld(Minecraft client) {
        phaseTicks++;
        if (isJoined(context(0))) {
            LOGGER.info("Compatibility test for {}: network world loaded", MOD_ID);
            TabTitleDisplaySmokeTest.verify(context(0));
            TabManager.createTab();
            require(TabManager.activeId() == 1, "Creating a second context did not select tab 1");
            require(TabManager.contexts().size() == 2, "Expected exactly two contexts in compatibility test");
            require(context(0).levelRenderer != context(1).levelRenderer, "Tab renderers were unexpectedly shared");
            if ("voicechat".equals(MOD_ID)) {
                require(
                    SimpleVoiceChatCompatibility.verifyBackgroundCallbacksAreSuppressed(0),
                    "Simple Voice Chat JOIN/DISCONNECT callbacks were not isolated from the inactive tab"
                );
            }
            if ("baritone".equals(MOD_ID)) {
                String secondAddress = environmentValue("TABBY_SERVER_B", "127.0.0.1:25566");
                ServerData secondServer = new ServerData(
                    "Tabby Baritone background server",
                    secondAddress,
                    ServerData.Type.OTHER
                );
                ConnectScreen.startConnecting(
                    client.screen,
                    client,
                    ServerAddress.parseString(secondAddress),
                    secondServer,
                    false,
                    null
                );
                LOGGER.info("Baritone compatibility test: connecting secondary tab to {}", secondAddress);
                phase = Phase.WAIT_FOR_SECOND_WORLD;
                phaseTicks = 0;
                return;
            }
            validateActiveContext(client);
            phase = Phase.SWITCHING;
            switchTicks = 0;
        } else if (phaseTicks > JOIN_TIMEOUT_TICKS) {
            throw new IllegalStateException("Compatibility test timed out waiting for world; mod=" + MOD_ID);
        }
    }

    private static void waitForSecondWorld(Minecraft client) {
        phaseTicks++;
        if (isJoined(context(1))) {
            require(isJoined(context(0)),
                "The original main tab lost its world while the second Baritone test server connected");
            require(TabManager.activeId() == 1,
                "The secondary Baritone test server did not remain the visible tab");
            baritoneMainTickCount = 0;
            baritoneForeignTickCount = 0;
            registerBaritoneTickProbe(client);
            validateActiveContext(client);
            LOGGER.info("Baritone compatibility test: both tab worlds are live; observing background main-tab ticks");
            phase = Phase.SWITCHING;
            switchTicks = 0;
        } else if (phaseTicks > JOIN_TIMEOUT_TICKS) {
            throw new IllegalStateException(
                "Baritone compatibility test timed out waiting for the secondary world: "
                    + connectionState(context(1))
            );
        }
    }

    private static void registerBaritoneTickProbe(Minecraft client) {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object primary = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object eventBus = primary.getClass().getMethod("getGameEventHandler").invoke(primary);
            Class<?> listenerType = Class.forName("baritone.api.event.listener.IGameEventListener");
            Object probe = Proxy.newProxyInstance(
                listenerType.getClassLoader(),
                new Class<?>[]{listenerType},
                (proxy, method, arguments) -> {
                    if ("onTick".equals(method.getName())) {
                        TabContext main = context(0);
                        if (TabManager.isBaritoneContextAllowed()
                            && main != null
                            && client.level == main.level
                            && client.player == main.player) {
                            baritoneMainTickCount++;
                        } else {
                            baritoneForeignTickCount++;
                        }
                    }
                    if ("toString".equals(method.getName())) return "TabbyBaritoneMainTabProbe";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == arguments[0];
                    return null;
                }
            );
            eventBus.getClass()
                .getMethod("registerEventListener", listenerType)
                .invoke(eventBus, probe);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not register the Baritone background-tick probe", exception);
        }
    }

    private static void exerciseWorldSwitching(Minecraft client) {
        switchTicks++;
        validateActiveContext(client);

        if (switchTicks % SWITCH_INTERVAL_TICKS == 0) {
            TabManager.switchToNext();
            validateActiveContext(client);
        }

        if (switchTicks >= REQUIRED_SWITCH_TICKS) {
            require(isJoined(context(0)), "Switching caused the compatibility test's server connection to drop");
            if ("baritone".equals(MOD_ID)) {
                require(baritoneMainTickCount >= REQUIRED_SWITCH_TICKS / 2,
                    "Baritone did not receive enough main-tab tick callbacks while that tab was hidden ("
                        + baritoneMainTickCount + ")");
                require(baritoneForeignTickCount == 0,
                    "Baritone tick callbacks ran against a non-main Tabby context ("
                        + baritoneForeignTickCount + ")");
            }
            LOGGER.info(
                "Tabby compatibility smoke test passed for {}: world rendering and six context switches completed.",
                MOD_ID
            );
            client.stop();
        }
    }

    private static void validateActiveContext(Minecraft client) {
        TabContext networkTab = context(0);
        TabContext menuTab = context(1);
        require(isJoined(networkTab), "The network tab lost its world, player, listener, or connection");
        require(menuTab != null, "The second tab context disappeared");
        require(networkTab.levelRenderer != menuTab.levelRenderer, "The two tabs share a LevelRenderer");
        require(client.levelRenderer == context(TabManager.activeId()).levelRenderer,
            "Minecraft.levelRenderer does not match the active tab");

        if ("baritone".equals(MOD_ID)) {
            require(isJoined(menuTab), "The secondary Baritone test tab lost its world or connection");
            TabContext selected = context(TabManager.activeId());
            require(client.level == selected.level, "Baritone test did not restore the selected tab's world");
            require(client.player == selected.player, "Baritone test did not restore the selected tab's player");
            require(client.getConnection() == selected.clientConnection,
                "Baritone test did not restore the selected tab's connection");
            return;
        }

        if (TabManager.activeId() == 0) {
            require(client.level == networkTab.level, "Active network level was not restored");
            require(client.player == networkTab.player, "Active network player was not restored");
            require(client.getConnection() == networkTab.clientConnection, "Active network listener was not restored");
            require(client.level != menuTab.level && client.player != menuTab.player,
                "The menu tab retained state from the network tab");
        } else {
            require(client.level == menuTab.level, "Menu tab level state was not restored");
            require(client.player == menuTab.player, "Menu tab player state was not restored");
            require(client.getConnection() == null, "Menu tab inherited the network connection");
        }

        if ("voicechat".equals(MOD_ID)) {
            boolean voiceSessionExpected = TabManager.activeId() == 0 && isJoined(networkTab);
            require(
                SimpleVoiceChatCompatibility.hasActiveClientSession() == voiceSessionExpected,
                "Simple Voice Chat session does not follow the selected tab"
            );
        }
    }

    private static void verifyConnectionOwnershipCleanup() {
        int ownerId = TabManager.activeId();
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        PacketListener handshakeListener = createProbeListener();
        PacketListener playListener = createProbeListener();

        ConnectionOwnershipRegistry.registerConnection(connection, ownerId);
        ConnectionOwnershipRegistry.registerListener(connection, handshakeListener, ownerId);
        ConnectionOwnershipRegistry.registerListener(connection, playListener, ownerId);
        require(
            ConnectionOwnershipRegistry.ownerIdFor(handshakeListener) == ownerId
                && ConnectionOwnershipRegistry.ownerIdFor(playListener) == ownerId,
            "Connection ownership was not recorded for every protocol listener"
        );

        ConnectionOwnershipRegistry.removeConnection(connection, playListener);
        require(
            ConnectionOwnershipRegistry.ownerIdFor(handshakeListener) < 0
                && ConnectionOwnershipRegistry.ownerIdFor(playListener) < 0,
            "Disconnect cleanup retained a listener from an earlier protocol phase"
        );
        LOGGER.info("Compatibility test: all protocol listeners are released when a connection closes");
    }

    private static PacketListener createProbeListener() {
        return (PacketListener) Proxy.newProxyInstance(
            PacketListener.class.getClassLoader(),
            new Class<?>[]{PacketListener.class},
            (proxy, method, arguments) -> null
        );
    }

    private static void verifyExpectedModLoaded() {
        if (MOD_ID.isBlank()) {
            throw new IllegalStateException("TABBY_COMPAT_MOD must identify the compatibility mod under test");
        }
        require(
            FabricLoader.getInstance().isModLoaded(MOD_ID),
            "The expected compatibility mod '" + MOD_ID + "' was not loaded"
        );
        if ("iris".equals(MOD_ID)) {
            require(FabricLoader.getInstance().isModLoaded("sodium"),
                "Iris compatibility test requires its Sodium dependency to be loaded too");
        }
        if ("modmenu".equals(MOD_ID)) {
            dev.tabby.compat.ModMenuIntegrationSmokeTest.verify();
        }
        if ("voicechat".equals(MOD_ID)) {
            require(
                SimpleVoiceChatCompatibility.isAvailable(),
                "Simple Voice Chat compatibility bridge was not applied"
            );
        }
        LOGGER.info("Compatibility test: verified optional mod '{}' is loaded", MOD_ID);
    }

    private static boolean isJoined(TabContext context) {
        return context != null
            && context.level != null
            && context.player != null
            && context.clientConnection != null
            && context.networkConnection != null
            && context.networkConnection.isConnected();
    }

    private static String connectionState(TabContext context) {
        if (context == null) {
            return "missing context";
        }
        return "level=" + (context.level != null)
            + ", player=" + (context.player != null)
            + ", connecting=" + context.connecting
            + ", connectionLive=" + (context.networkConnection != null
                && context.networkConnection.isConnected());
    }

    private static TabContext context(int id) {
        return TabManager.contexts().stream()
            .filter(tab -> tab.id() == id)
            .findFirst()
            .orElse(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Tabby compatibility smoke test failed (" + MOD_ID + "): " + message);
        }
    }

    private static String environmentValue(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
