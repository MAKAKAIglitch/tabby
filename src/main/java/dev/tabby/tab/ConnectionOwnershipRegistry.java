package dev.tabby.tab;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Maintains identity-based ownership links between network connections,
 * packet listeners, and their Tabby tab.
 *
 * Connection callbacks may arrive from Netty threads, so both identity maps
 * are independently synchronized. Tab lifecycle decisions remain in TabManager.
 */
final class ConnectionOwnershipRegistry {
    private static final Map<Connection, Integer> CONNECTION_OWNERS = new IdentityHashMap<>();
    private static final Map<PacketListener, Integer> LISTENER_OWNERS = new IdentityHashMap<>();

    private ConnectionOwnershipRegistry() {
    }

    static void registerConnection(Connection connection, int tabId) {
        if (connection == null) {
            return;
        }
        synchronized (CONNECTION_OWNERS) {
            CONNECTION_OWNERS.putIfAbsent(connection, tabId);
        }
    }

    static int registerListener(Connection connection, PacketListener listener, int fallbackTabId) {
        if (connection == null || listener == null) {
            return fallbackTabId;
        }

        final int ownerId;
        synchronized (CONNECTION_OWNERS) {
            ownerId = CONNECTION_OWNERS.computeIfAbsent(connection, ignored -> fallbackTabId);
        }
        synchronized (LISTENER_OWNERS) {
            LISTENER_OWNERS.put(listener, ownerId);
        }

        if (listener instanceof TabOwnedListener owned) {
            owned.tabby$setTabId(ownerId);
        }
        return ownerId;
    }

    static int ownerIdFor(PacketListener listener) {
        if (listener == null) {
            return -1;
        }
        if (listener instanceof TabOwnedListener owned && owned.tabby$getTabId() >= 0) {
            return owned.tabby$getTabId();
        }

        synchronized (LISTENER_OWNERS) {
            return LISTENER_OWNERS.getOrDefault(listener, -1);
        }
    }

    static void removeConnection(Connection connection, PacketListener listener) {
        if (connection != null) {
            synchronized (CONNECTION_OWNERS) {
                CONNECTION_OWNERS.remove(connection);
            }
        }
        if (listener != null) {
            synchronized (LISTENER_OWNERS) {
                LISTENER_OWNERS.remove(listener);
            }
        }
    }

    static void removeTab(int tabId) {
        synchronized (CONNECTION_OWNERS) {
            CONNECTION_OWNERS.entrySet().removeIf(entry -> entry.getValue() == tabId);
        }
        synchronized (LISTENER_OWNERS) {
            LISTENER_OWNERS.entrySet().removeIf(entry -> entry.getValue() == tabId);
        }
    }

    static void clear() {
        synchronized (CONNECTION_OWNERS) {
            CONNECTION_OWNERS.clear();
        }
        synchronized (LISTENER_OWNERS) {
            LISTENER_OWNERS.clear();
        }
    }
}
