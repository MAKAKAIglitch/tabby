package dev.tabby.mixin.optional;

import dev.tabby.tab.TabManager;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Baritone has one process-global event bus. Its process ticks are scoped to the
 * original main tab, while world/player events from other tabs are rejected.
 */
@Mixin(targets = "baritone.event.GameEventHandler", remap = false)
public abstract class BaritoneGameEventHandlerMixin {
    private static volatile TickEventFactory tabby$tickEventFactory;

    @WrapMethod(method = "onTick")
    private void tabby$tickInMainTab(@Coerce Object event, Operation<Void> original) {
        Object mainEvent = tabby$eventForMainWorld(event);
        TabManager.runBaritoneTickInMain(() -> original.call(mainEvent));
    }

    @WrapMethod(method = "onPostTick")
    private void tabby$postTickInMainTab(@Coerce Object event, Operation<Void> original) {
        Object mainEvent = tabby$eventForMainWorld(event);
        TabManager.runBaritoneTickInMain(() -> original.call(mainEvent));
    }

    private static Object tabby$eventForMainWorld(Object event) {
        if (TabManager.isBaritoneContextAllowed() || !TabManager.isBaritoneMainWorldStable()) {
            return event;
        }

        try {
            TickEventFactory factory = tabby$tickEventFactory;
            if (factory == null || !factory.eventClass.equals(event.getClass())) {
                synchronized (BaritoneGameEventHandlerMixin.class) {
                    factory = tabby$tickEventFactory;
                    if (factory == null || !factory.eventClass.equals(event.getClass())) {
                        factory = new TickEventFactory(event.getClass());
                        tabby$tickEventFactory = factory;
                    }
                }
            }
            return factory.asInEvent(event);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Could not adapt Baritone's background TickEvent to the main world", exception);
        }
    }

    private static final class TickEventFactory {
        private final Class<?> eventClass;
        private final Method getState;
        private final Method getCount;
        private final Constructor<?> constructor;
        private final Object inType;

        @SuppressWarnings({"unchecked", "rawtypes"})
        private TickEventFactory(Class<?> eventClass) throws ReflectiveOperationException {
            this.eventClass = eventClass;
            this.getState = eventClass.getMethod("getState");
            this.getCount = eventClass.getMethod("getCount");
            Class<?> typeClass = eventClass.getMethod("getType").getReturnType();
            this.constructor = eventClass.getConstructor(this.getState.getReturnType(), typeClass, int.class);
            this.inType = Enum.valueOf((Class) typeClass, "IN");
        }

        private Object asInEvent(Object event) throws ReflectiveOperationException {
            Object state = this.getState.invoke(event);
            int count = ((Number) this.getCount.invoke(event)).intValue();
            return this.constructor.newInstance(state, this.inType, count);
        }
    }

    @Inject(
        method = {
            "onPlayerUpdate",
            "onSendChatMessage",
            "onPreTabComplete",
            "onChunkEvent",
            "onBlockChange",
            "onRenderPass",
            "onWorldEvent",
            "onSendPacket",
            "onReceivePacket",
            "onPlayerRotationMove",
            "onPlayerSprintState",
            "onBlockInteract",
            "onPlayerDeath",
            "onPathEvent"
        },
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void tabby$restrictEventsToMainTab(CallbackInfo ci) {
        if (!TabManager.isBaritoneContextAllowed()) {
            ci.cancel();
        }
    }
}
