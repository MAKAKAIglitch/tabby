package dev.tabby.tab;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prevents a hidden LocalPlayer from replaying a server velocity impulse after its
 * physics has been paused by tab switching. This only marks the local player's
 * own velocity while the owning context is hidden; active-tab knockback is untouched.
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
            Vec3 discardedVelocity = context.player.getDeltaMovement();
            context.player.setDeltaMovement(Vec3.ZERO);
            LOGGER.info(
                "Cleared deferred background knockback before activating tab {} (velocity={})",
                context.id(),
                discardedVelocity
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
