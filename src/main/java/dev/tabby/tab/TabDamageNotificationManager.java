package dev.tabby.tab;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coalesces damage events from inactive local players into one global chat alert
 * and UI sound. Packet callbacks are resolved to their owning tab before an event
 * is accepted; presentation runs later from the ordinary client tick, after the
 * previous tab's global Minecraft state has been restored.
 */
final class TabDamageNotificationManager {
    private static final long NOTIFICATION_COOLDOWN_TICKS = 12L;

    private static final Map<Integer, PendingDamage> PENDING = new LinkedHashMap<>();
    private static final Map<Integer, Long> LAST_NOTIFICATION_TICK = new HashMap<>();
    private static long clientTick;

    private TabDamageNotificationManager() {
    }

    static void onPlayerDamage(ClientPacketListener listener, int entityId) {
        if (listener == null || !TabbyConfig.notifyBackgroundDamage()) {
            return;
        }

        Minecraft client = TabManager.clientInstance();
        if (client == null) {
            return;
        }

        int ownerId = ConnectionOwnershipRegistry.ownerIdFor(listener);
        if (ownerId < 0) {
            return;
        }

        Runnable scopedRecord = () -> {
            if (!TabbyConfig.notifyBackgroundDamage() || ownerId == TabRegistry.activeId()) {
                return;
            }

            TabContext context = TabRegistry.get(ownerId);
            if (context == null
                || context.player == null
                || context.player.getId() != entityId
                || context.clientConnection != listener) {
                return;
            }

            Connection connection = listener.getConnection();
            if (context.networkConnection != connection) {
                return;
            }

            String title = context.displayTitle();
            if (title == null || title.isBlank()) {
                title = "Tab " + ownerId;
            }

            PendingDamage previous = PENDING.get(ownerId);
            int hits = previous == null || previous.connection() != connection
                ? 1
                : previous.hits() + 1;
            PENDING.put(ownerId, new PendingDamage(title, hits, connection));
        };

        Runnable onClientThread = () -> {
            if (TabManager.currentContextId() == ownerId) {
                scopedRecord.run();
            } else {
                TabManager.runIn(ownerId, scopedRecord);
            }
        };

        if (client.isSameThread()) {
            onClientThread.run();
        } else {
            client.execute(onClientThread);
        }
    }

    static void tick(Minecraft client) {
        if (!TabbyConfig.notifyBackgroundDamage()) {
            PENDING.clear();
            LAST_NOTIFICATION_TICK.clear();
            return;
        }

        // Advance the cooldown clock even while the queue is empty.
        clientTick++;
        if (client == null || client.gui == null || PENDING.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<Integer, PendingDamage>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, PendingDamage> entry = iterator.next();
            int tabId = entry.getKey();
            Long lastTick = LAST_NOTIFICATION_TICK.get(tabId);
            if (lastTick != null && clientTick - lastTick < NOTIFICATION_COOLDOWN_TICKS) {
                continue;
            }

            PendingDamage pending = entry.getValue();
            TabContext current = TabRegistry.get(tabId);
            if (current == null || current.networkConnection != pending.connection()) {
                iterator.remove();
                continue;
            }

            Component damageMessage = pending.hits() == 1
                ? Component.translatable("tabby.notification.damage", pending.title())
                : Component.translatable(
                    "tabby.notification.damage.multiple",
                    pending.title(),
                    pending.hits()
                );
            client.gui.getChat().addMessage(
                Component.literal("[Tabby] ").withStyle(ChatFormatting.GOLD)
                    .append(damageMessage.copy().withStyle(ChatFormatting.YELLOW))
            );
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 0.9F));

            LAST_NOTIFICATION_TICK.put(tabId, clientTick);
            iterator.remove();
        }
    }

    static void removeTab(int tabId) {
        PENDING.remove(tabId);
        LAST_NOTIFICATION_TICK.remove(tabId);
    }

    static void clear() {
        PENDING.clear();
        LAST_NOTIFICATION_TICK.clear();
        clientTick = 0L;
    }

    static int pendingDamageCount(int tabId) {
        PendingDamage pending = PENDING.get(tabId);
        return pending == null ? 0 : pending.hits();
    }

    static void clearPending(int tabId) {
        PENDING.remove(tabId);
        LAST_NOTIFICATION_TICK.remove(tabId);
    }

    private record PendingDamage(String title, int hits, Connection connection) {
    }
}
