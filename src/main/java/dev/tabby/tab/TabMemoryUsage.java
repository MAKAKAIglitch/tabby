package dev.tabby.tab;

import net.minecraft.client.multiplayer.ClientLevel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cached heuristic for memory retained by each tab's world data.
 *
 * Java does not expose an exact per-object retained-heap measurement. The estimate
 * uses loaded chunks, tracked entities, and a small per-tab baseline.
 * All accesses are client-thread confined, so no synchronized map access is needed.
 */
public final class TabMemoryUsage {
    private static final long MIB = 1024L * 1024L;
    private static final long MENU_BASE_BYTES = 2L * MIB;
    private static final long WORLD_BASE_BYTES = 20L * MIB;
    private static final long ESTIMATED_BYTES_PER_CHUNK = 256L * 1024L;
    private static final long ESTIMATED_BYTES_PER_ENTITY = 16L * 1024L;
    private static final Map<Integer, Sample> CACHE = new HashMap<>();

    private TabMemoryUsage() {
    }

    public static void refresh(List<TabContext> contexts) {
        Set<Integer> presentIds = new HashSet<>(contexts.size());
        for (TabContext context : contexts) {
            presentIds.add(context.id());
            CACHE.put(context.id(), measure(context));
        }
        CACHE.keySet().removeIf(id -> !presentIds.contains(id));
    }

    public static Sample sample(TabContext context) {
        if (context == null) return new Sample(0, 0, 0, false);
        return CACHE.computeIfAbsent(context.id(), ignored -> measure(context));
    }

    public static void remove(int tabId) { CACHE.remove(tabId); }
    public static void clear() { CACHE.clear(); }

    private static Sample measure(TabContext context) {
        ClientLevel level = context.level;
        if (level == null) return new Sample((int) (MENU_BASE_BYTES / MIB), 0, 0, false);

        int chunks = Math.max(0, level.getChunkSource().getLoadedChunksCount());
        int entities = Math.max(0, level.getEntityCount());
        long bytes = WORLD_BASE_BYTES
            + (long) chunks * ESTIMATED_BYTES_PER_CHUNK
            + (long) entities * ESTIMATED_BYTES_PER_ENTITY;
        int mib = (int) Math.min(Integer.MAX_VALUE, Math.max(1, (bytes + MIB - 1) / MIB));
        return new Sample(mib, chunks, entities, true);
    }

    public record Sample(int estimatedMiB, int loadedChunks, int trackedEntities, boolean hasWorld) {
        public String compactLabel() {
            return "~" + this.estimatedMiB + "MB";
        }

        public String hudLabel(String tabTitle) {
            String title = tabTitle == null || tabTitle.isBlank() ? "Main Menu" : tabTitle;
            if (!this.hasWorld) return "Tabby | " + title + " | RAM ~" + this.estimatedMiB + " MB";
            return "Tabby | " + title + " | RAM ~" + this.estimatedMiB
                + " MB | " + this.loadedChunks + " chunks | " + this.trackedEntities + " entities";
        }
    }
}
