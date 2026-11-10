package dev.tabby.mixin.optional;

import dev.tabby.compat.MeteorTabIsolation;
import dev.tabby.tab.TabManager;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Prevents Meteor's game-tick handlers from running while the active client has no world.
 *
 * Meteor modules such as AutoBreed and Nametags can dereference mc.level from their
 * tick callbacks without checking for a title-screen or disconnect state. Since Tabby
 * permits a title-screen tab while another tab remains connected, skip only Meteor's
 * TickEvent.Pre/Post dispatch until an active world is available again.
 *
 * This mixin is optional and has no compile-time dependency on Meteor or Orbit.
 */
@Pseudo
@Mixin(targets = "meteordevelopment.orbit.EventBus", remap = false)
public abstract class MeteorTickEventBusMixin {
    @Unique
    private static final Logger tabby$LOGGER = LoggerFactory.getLogger("Tabby");

    @Unique
    private static boolean tabby$loggedMissingWorldTick;

    @Unique
    private static volatile Object tabby$meteorEventBus;

    @Unique
    private static volatile boolean tabby$meteorEventBusResolved;

    @Unique
    private boolean tabby$isMeteorEventBus() {
        if (!tabby$meteorEventBusResolved) {
            synchronized (MeteorTickEventBusMixin.class) {
                if (!tabby$meteorEventBusResolved) {
                    try {
                        Class<?> meteorClient = Class.forName("meteordevelopment.meteorclient.MeteorClient");
                        tabby$meteorEventBus = meteorClient.getField("EVENT_BUS").get(null);
                    } catch (ReflectiveOperationException exception) {
                        tabby$LOGGER.debug("Could not resolve Meteor's event bus", exception);
                    } finally {
                        tabby$meteorEventBusResolved = true;
                    }
                }
            }
        }
        return tabby$meteorEventBus == (Object) this;
    }

    @WrapMethod(method = "post(Ljava/lang/Object;)Ljava/lang/Object;")
    private Object tabby$dispatchMeteorEventInOwningTab(@Coerce Object event, Operation<Object> original) {
        if (event == null || !tabby$isMeteorEventBus()) {
            return original.call(event);
        }

        String eventClass = event.getClass().getName();
        boolean isWorldTick = eventClass.equals("meteordevelopment.meteorclient.events.world.TickEvent$Pre")
            || eventClass.equals("meteordevelopment.meteorclient.events.world.TickEvent$Post");

        // Some Meteor modules dereference the world during menu/disconnect ticks.
        // Do not dispatch these events when the currently installed context has no world.
        if (isWorldTick && Minecraft.getInstance().level == null) {
            if (!tabby$loggedMissingWorldTick) {
                tabby$LOGGER.debug("Skipping Meteor tick event because no installed Minecraft world is loaded");
                tabby$loggedMissingWorldTick = true;
            }
            return event;
        }
        if (isWorldTick) {
            tabby$loggedMissingWorldTick = false;
        }

        int ownerTab = TabManager.executionTabId();
        int selectedTab = TabManager.activeId();
        if (ownerTab == selectedTab) {
            return original.call(event);
        }

        return MeteorTabIsolation.dispatchBackgroundEvent(
            ownerTab,
            selectedTab,
            () -> original.call(event)
        );
    }

}
