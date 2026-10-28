package dev.matvey.tabby.tab;

import com.mojang.blaze3d.platform.InputConstants;
import dev.matvey.tabby.mixin.MinecraftAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
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
    private static final ThreadLocal<Integer> EXECUTION_CONTEXT = new ThreadLocal<>();
    private static final int MAX_PROTOTYPE_TABS = 4;

    private static Minecraft client;
    private static volatile int activeId = -1;
    private static int nextId;
    private static KeyMapping createTabKey;
    private static KeyMapping nextTabKey;

    private TabManager() {
    }

    public static void initialize(Minecraft minecraft) {
        if (client != null) {
            return;
        }

        client = minecraft;
        TabContext initial = TabContext.capture(client, nextId++, "Main Menu");
        TABS.put(initial.id(), initial);
        activeId = initial.id();

        createTabKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.tabby.create_test_tab",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F6,
            "category.tabby"
        ));
        nextTabKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.tabby.next_test_tab",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F7,
            "category.tabby"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(TabManager::onClientTick);
        LOGGER.debug("Initialized tab context {}", initial.id());
    }

    private static void onClientTick(Minecraft minecraft) {
        if (createTabKey.consumeClick()) {
            createTestTab();
        }
        if (nextTabKey.consumeClick()) {
            switchToNext();
        }
        tickBackgroundConnections();
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

    public static void createTestTab() {
        if (client == null || TABS.size() >= MAX_PROTOTYPE_TABS) {
            LOGGER.debug("Test tab creation ignored (not initialized or prototype limit reached)");
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
            target.applyTo(client, false);
            target.levelRenderer.setLevel(target.level);
            target.particleEngine.setLevel(target.level);
            client.setScreen(target.screen);
            activeId = tabId;
            target.captureFrom(client);
            LOGGER.debug("Switched active tab to {} ({})", target.id(), target.title());
        } catch (RuntimeException | Error exception) {
            LOGGER.debug("Failed to switch to tab {}", tabId, exception);
            if (previous != null) {
                previous.applyTo(client, false);
                client.setScreen(previous.screen);
            }
            throw exception;
        }
    }

    public static Runnable wrapPacketTask(PacketListener listener, Runnable task) {
        if (client == null) {
            return task;
        }

        int ownerId = ownerIdFor(listener);
        if (ownerId < 0 || ownerId == currentContextId()) {
            return task;
        }
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
            target.captureFrom(client);
            if (previous != null) {
                previous.applyTo(client, true);
            }
            if (previousExecutionId == null) {
                EXECUTION_CONTEXT.remove();
            } else {
                EXECUTION_CONTEXT.set(previousExecutionId);
            }
        }
    }

    public static void onConnectionCreated(Connection connection) {
        if (connection == null) {
            return;
        }
        synchronized (CONNECTION_OWNERS) {
            CONNECTION_OWNERS.putIfAbsent(connection, currentContextId());
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

            context.networkConnection = connection;
            if (listener instanceof TickablePacketListener tickable) {
                context.packetListener = tickable;
            }
            if (listener instanceof ClientPacketListener playListener) {
                context.clientConnection = playListener;
                context.serverData = playListener.getServerData();
            }
            LOGGER.debug("Assigned {} to tab {}", listener.getClass().getSimpleName(), ownerId);
        });
    }

    private static int ownerIdFor(PacketListener listener) {
        if (listener instanceof TabOwnedListener owned && owned.tabby$getTabId() >= 0) {
            return owned.tabby$getTabId();
        }

        if (listener instanceof ClientCommonPacketListenerImpl common) {
            synchronized (CONNECTION_OWNERS) {
                return CONNECTION_OWNERS.getOrDefault(common.getConnection(), -1);
            }
        }
        return -1;
    }

    private static void tickBackgroundConnections() {
        if (client == null) {
            return;
        }

        int selectedId = activeId;
        for (TabContext context : List.copyOf(TABS.values())) {
            if (context.id() == selectedId || context.packetListener == null || context.networkConnection == null) {
                continue;
            }
            if (!context.networkConnection.isConnected()) {
                continue;
            }

            runIn(context.id(), () -> {
                context.packetListener.tick();
                context.networkConnection.tick();
            });
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
