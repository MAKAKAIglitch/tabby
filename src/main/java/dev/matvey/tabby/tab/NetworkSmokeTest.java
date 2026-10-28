package dev.matvey.tabby.tab;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional end-to-end test used by CI with two local offline-mode servers.
 * It is enabled only when TABBY_NETWORK_SMOKE_TEST=1 is present.
 */
public final class NetworkSmokeTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    private static final boolean ENABLED = "1".equals(System.getenv("TABBY_NETWORK_SMOKE_TEST"));
    private static final int JOIN_TIMEOUT_TICKS = 1200;
    private static final int REQUIRED_SWITCH_TICKS = 2400;
    private static final int SWITCH_INTERVAL_TICKS = 40;

    private enum Phase {
        START_SERVER_A,
        WAIT_FOR_SERVER_A,
        WAIT_FOR_SERVER_B,
        SWITCHING
    }

    private static Phase phase = Phase.START_SERVER_A;
    private static int phaseTicks;
    private static int activeTicks;
    private static boolean successLogged;

    private NetworkSmokeTest() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void tick(Minecraft client) {
        if (!ENABLED) {
            return;
        }

        switch (phase) {
            case START_SERVER_A -> startServerA(client);
            case WAIT_FOR_SERVER_A -> waitForServerA(client);
            case WAIT_FOR_SERVER_B -> waitForServerB(client);
            case SWITCHING -> exerciseConnections(client);
        }
    }

    private static void startServerA(Minecraft client) {
        if (++phaseTicks < 20) {
            return;
        }

        require(TabManager.activeId() == 0, "Expected the first tab to be active before connecting to server A");
        String address = environmentAddress("TABBY_SERVER_A", "127.0.0.1:25565");
        connect(client, "Tabby CI server A", address);
        LOGGER.info("Network smoke test: connecting tab 0 to {}", address);
        phase = Phase.WAIT_FOR_SERVER_A;
        phaseTicks = 0;
    }

    private static void waitForServerA(Minecraft client) {
        phaseTicks++;
        TabContext serverA = context(0);
        if (isJoined(serverA)) {
            LOGGER.info("Network smoke test: tab 0 joined server A");
            TabManager.createTestTab();
            require(TabManager.activeId() == 1, "Creating the second tab did not select tab 1");

            String address = environmentAddress("TABBY_SERVER_B", "127.0.0.1:25566");
            connect(client, "Tabby CI server B", address);
            LOGGER.info("Network smoke test: connecting tab 1 to {}", address);
            phase = Phase.WAIT_FOR_SERVER_B;
            phaseTicks = 0;
        } else if (phaseTicks > JOIN_TIMEOUT_TICKS) {
            fail("Tab 0 did not join server A within " + JOIN_TIMEOUT_TICKS + " ticks");
        }
    }

    private static void waitForServerB(Minecraft client) {
        phaseTicks++;
        if (isJoined(context(1))) {
            validateConnections(client);

            TabManager.switchTo(0);
            Screen activeScreen = client.screen;
            TabContext background = context(1);
            Screen previousBackgroundScreen = background.screen;
            Screen replacementScreen = new TitleScreen();
            TabManager.runIn(1, () -> client.setScreen(replacementScreen));
            require(background.screen == replacementScreen, "Background screen state was not retained");
            require(client.screen == activeScreen, "Background screen change replaced the active screen");
            background.screen = previousBackgroundScreen;
            TabManager.switchTo(1);
            validateConnections(client);

            LOGGER.info("Network smoke test: tab 1 joined server B; starting connection-isolation test");
            phase = Phase.SWITCHING;
            phaseTicks = 0;
        } else if (phaseTicks > JOIN_TIMEOUT_TICKS) {
            fail("Tab 1 did not join server B within " + JOIN_TIMEOUT_TICKS + " ticks");
        }
    }

    private static void exerciseConnections(Minecraft client) {
        activeTicks++;
        validateConnections(client);

        if (activeTicks % SWITCH_INTERVAL_TICKS == 0) {
            TabManager.switchToNext();
            validateConnections(client);
        }

        if (activeTicks % 200 == 0) {
            LOGGER.info(
                "Network smoke test: {} ticks elapsed; both connections live; active tab {}",
                activeTicks,
                TabManager.activeId()
            );
        }

        if (!successLogged && activeTicks >= REQUIRED_SWITCH_TICKS) {
            successLogged = true;
            LOGGER.info(
                "Tabby network smoke test passed: both connections remained live for {} ticks.",
                activeTicks
            );
        }
    }

    private static void connect(Minecraft client, String label, String address) {
        ServerData serverData = new ServerData(label, address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(
            client.screen,
            client,
            ServerAddress.parseString(address),
            serverData,
            false,
            null
        );
    }

    private static void validateConnections(Minecraft client) {
        TabContext serverA = context(0);
        TabContext serverB = context(1);
        require(isJoined(serverA), "Server A disconnected or lost its client world/player");
        require(isJoined(serverB), "Server B disconnected or lost its client world/player");
        require(serverA.level != serverB.level, "Both tabs reference the same ClientLevel instance");
        require(serverA.player != serverB.player, "Both tabs reference the same LocalPlayer instance");
        require(serverA.networkConnection != serverB.networkConnection, "Both tabs reference the same network connection");

        TabContext active = context(TabManager.activeId());
        require(active != null, "The active tab has no TabContext");
        require(client.level == active.level, "Minecraft.level does not match the active tab");
        require(client.player == active.player, "Minecraft.player does not match the active tab");
        require(client.getConnection() == active.clientConnection, "Minecraft.getConnection() does not match the active tab");
    }

    private static boolean isJoined(TabContext context) {
        return context != null
            && context.level != null
            && context.player != null
            && context.clientConnection != null
            && context.networkConnection != null
            && context.networkConnection.isConnected();
    }

    private static TabContext context(int id) {
        return TabManager.contexts().stream()
            .filter(tab -> tab.id() == id)
            .findFirst()
            .orElse(null);
    }

    private static String environmentAddress(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            fail(message);
        }
    }

    private static void fail(String message) {
        LOGGER.error("Network smoke test failed: {}", message);
        throw new IllegalStateException("Tabby network smoke test failed: " + message);
    }
}
