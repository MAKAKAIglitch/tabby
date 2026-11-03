package dev.tabby.tab;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns tab identity, ordering, and the active-tab pointer.
 *
 * Immutable ordered snapshots are rebuilt only when membership/order changes.
 * Hot paths can reuse them without allocating a defensive copy every frame or tick.
 */
final class TabRegistry {
    private static final LinkedHashMap<Integer, TabContext> TABS = new LinkedHashMap<>();
    private static final Map<Integer, TabContext> READ_ONLY_VIEW = Collections.unmodifiableMap(TABS);
    private static volatile List<TabContext> contextSnapshot = List.of();
    private static volatile List<Integer> idSnapshot = List.of();
    private static volatile int activeId = -1;
    private static int nextId;

    private TabRegistry() {
    }

    static TabContext get(int id) { return TABS.get(id); }

    static void put(int id, TabContext context) {
        TABS.put(id, context);
        rebuildSnapshots();
    }

    static TabContext remove(int id) {
        TabContext removed = TABS.remove(id);
        if (removed != null) rebuildSnapshots();
        return removed;
    }

    static boolean contains(int id) { return TABS.containsKey(id); }
    static boolean isEmpty() { return TABS.isEmpty(); }
    static int size() { return TABS.size(); }
    static List<TabContext> values() { return contextSnapshot; }
    static List<Integer> ids() { return idSnapshot; }
    static int firstId() { return TABS.keySet().iterator().next(); }

    static void replaceOrder(List<TabContext> orderedContexts) {
        LinkedHashMap<Integer, TabContext> reordered = new LinkedHashMap<>();
        for (TabContext context : orderedContexts) reordered.put(context.id(), context);
        TABS.clear();
        TABS.putAll(reordered);
        rebuildSnapshots();
    }

    static void clear() {
        if (!TABS.isEmpty()) {
            TABS.clear();
            rebuildSnapshots();
        }
    }

    static int nextId() { return nextId++; }
    static int activeId() { return activeId; }
    static void setActiveId(int id) { activeId = id; }
    static Map<Integer, TabContext> view() { return READ_ONLY_VIEW; }

    private static void rebuildSnapshots() {
        // Structural writes are client-thread confined; volatile publication safely
        // exposes completed immutable snapshots to read-only callbacks.
        contextSnapshot = List.copyOf(TABS.values());
        idSnapshot = List.copyOf(TABS.keySet());
    }
}
