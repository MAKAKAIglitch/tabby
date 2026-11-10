package dev.tabby.tab;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks server velocity impulses received while a tab is hidden.
 *
 * The packet has already updated that tab's LocalPlayer. Activation must preserve
 * the velocity so the next normal player tick applies the knockback instead of
 * silently discarding it.
 */
final class TabMotionTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    private static final double MIN_KNOCKBACK_SQUARED = 1.0E-8;

    private TabMotionTracker() {
    }

    static void onPlayerVelocity(ClientPacketListener listener, int entityId) {
        if (listener == null) {
            return;
        }

        int ownerId = ConnectionOwnershipRegistry.ownerIdFor(listener);
        if (!isHiddenOwner(ownerId)) {
            return;
        }

        TabContext context = TabRegistry.get(ownerId);
        if (context == null
            || context.player == null
            || context.player.getId() != entityId
            || context.clientConnection != listener) {
            return;
        }

        context.deferredBackgroundKnockback = true;
        LOGGER.debug("Recorded a deferred own-player velocity packet for background tab {}", ownerId);
    }

    static void onPlayerExplosion(ClientPacketListener listener, double x, double y, double z) {
        if (listener == null || x * x + y * y + z * z < MIN_KNOCKBACK_SQUARED) {
            return;
        }

        int ownerId = ConnectionOwnershipRegistry.ownerIdFor(listener);
        if (!isHiddenOwner(ownerId)) {
            return;
        }

        TabContext context = TabRegistry.get(ownerId);
        if (context == null || context.player == null || context.clientConnection != listener) {
            return;
        }

        context.deferredBackgroundKnockback = true;
        LOGGER.debug("Recorded deferred explosion knockback for background tab {}", ownerId);
    }

    static void beforeActivate(TabContext context) {
        if (context == null || !context.deferredBackgroundKnockback) {
            return;
        }

        context.deferredBackgroundKnockback = false;
        if (context.player != null) {
            LOGGER.debug(
                "Preserved deferred background knockback for tab {} (velocity={})",
                context.id(),
                context.player.getDeltaMovement()
            );
        } else {
            LOGGER.debug("Cleared stale knockback marker for tab {} without a current LocalPlayer", context.id());
        }
    }

    private static boolean isHiddenOwner(int ownerId) {
        return ownerId >= 0
            && ownerId != TabRegistry.activeId()
            && ownerId == TabManager.currentContextId();
    }
}
