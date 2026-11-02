package dev.tabby.tab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns tab identity, ordering, and the active-tab pointer.
 *
 * All structural changes are made on Minecraft's client thread. The active ID is
 * volatile because mixin callbacks can inspect it from other execution contexts.
 * Callers receive snapshots or a read-only map view, never the mutable registry.
 */
final class TabRegistry {
    private static final LinkedHashMap<Integer, TabContext> TABS = new LinkedHashMap<>();
    private static final Map<Integer, TabContext> READ_ONLY_VIEW = Collections.unmodifiableMap(TABS);
    private static final Collection<TabContext> READ_ONLY_VALUES = Collections.unmodifiableCollection(TABS.values());

    private static volatile int activeId = -1;
    private static int nextId;

    private TabRegistry() {
    }

    static TabContext get(int id) {
        return TABS.get(id);
    }

    static void put(int id, TabContext context) {
        TABS.put(id, context);
    }

    static TabContext remove(int id) {
        return TABS.remove(id);
    }

    static boolean contains(int id) {
        return TABS.containsKey(id);
    }

    static boolean isEmpty() {
        return TABS.isEmpty();
    }

    static int size() {
        return TABS.size();
    }

    static Collection<TabContext> values() {
        return READ_ONLY_VALUES;
    }

    static List<Integer> ids() {
        return List.copyOf(TABS.keySet());
    }

    static int firstId() {
        return TABS.keySet().iterator().next();
    }

    static void replaceOrder(List<TabContext> orderedContexts) {
        LinkedHashMap<Integer, TabContext> reordered = new LinkedHashMap<>();
        for (TabContext context : orderedContexts) {
            reordered.put(context.id(), context);
        }
        TABS.clear();
        TABS.putAll(reordered);
    }

    static void clear() {
        TABS.clear();
    }

    static int nextId() {
        return nextId++;
    }

    static int activeId() {
        return activeId;
    }

    static void setActiveId(int id) {
        activeId = id;
    }

    static Map<Integer, TabContext> view() {
        return READ_ONLY_VIEW;
    }
}
