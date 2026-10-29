package dev.tabby.mixin.optional;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    @Inject(
        method = "post(Ljava/lang/Object;)Ljava/lang/Object;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void tabby$guardMeteorWorldTick(Object event, CallbackInfoReturnable<Object> cir) {
        if (event == null) {
            return;
        }

        String eventClass = event.getClass().getName();
        if (!eventClass.equals("meteordevelopment.meteorclient.events.world.TickEvent$Pre")
            && !eventClass.equals("meteordevelopment.meteorclient.events.world.TickEvent$Post")) {
            return;
        }

        if (Minecraft.getInstance().level != null) {
            tabby$loggedMissingWorldTick = false;
            return;
        }

        if (!tabby$loggedMissingWorldTick) {
            tabby$LOGGER.debug("Skipping Meteor tick event because no active Minecraft world is loaded");
            tabby$loggedMissingWorldTick = true;
        }

        // EventBus.post(T) returns its input event. Returning it unchanged preserves
        // EventBus semantics while preventing world-dependent Meteor handlers from running.
        cir.setReturnValue(event);
    }
}
