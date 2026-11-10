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
 * Keeps Meteor's single Orbit event bus aligned with the Tabby client context that owns
 * the event. Background packet events are dispatched against that tab's independent
 * Meteor Systems snapshot; menu/disconnect world ticks remain guarded.
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
