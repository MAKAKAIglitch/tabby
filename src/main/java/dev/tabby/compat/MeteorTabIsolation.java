package dev.tabby.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Keeps Meteor's one process-global Systems registry logically isolated per Tabby tab.
 *
 * No Meteor classes are linked at compile time. The bridge uses Meteor's public
 * serialization methods reflectively, so Meteor remains an optional integration.
 */
public final class MeteorTabIsolation {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    private static final String METEOR_MOD_ID = "meteor-client";
    private static final String SYSTEMS_CLASS = "meteordevelopment.meteorclient.systems.Systems";
    private static final String MODULES_CLASS = "meteordevelopment.meteorclient.systems.modules.Modules";
    private static final String CONFIG_CLASS = "meteordevelopment.meteorclient.systems.config.Config";
    private static final Set<String> SHARED_SYSTEMS = Set.of(
        "meteordevelopment.meteorclient.systems.accounts.Accounts",
        "meteordevelopment.meteorclient.systems.profiles.Profiles"
    );

    // Built-in game-facing systems are restored in dependency order. Any additional
    // serializable Systems registered by Meteor addons are added automatically before
    // Modules, so addon state is isolated without knowing its class at compile time.
    private static final List<String> PREFERRED_SYSTEM_ORDER = List.of(
        CONFIG_CLASS,
        "meteordevelopment.meteorclient.systems.friends.Friends",
        "meteordevelopment.meteorclient.systems.macros.Macros",
        "meteordevelopment.meteorclient.systems.waypoints.Waypoints",
        "meteordevelopment.meteorclient.systems.proxies.Proxies",
        "meteordevelopment.meteorclient.systems.hud.Hud"
    );

    private static final Map<Integer, Snapshot> TAB_STATES = new HashMap<>();
    private static final ThreadLocal<ArrayDeque<Integer>> TAB_STATE_SCOPES =
        ThreadLocal.withInitial(ArrayDeque::new);
    private static final AtomicLong ISOLATED_BACKGROUND_EVENTS = new AtomicLong();
    private static final AtomicLong BACKGROUND_TICK_COUNT = new AtomicLong();

    private static int pendingSourceTab = -1;
    private static int pendingTargetTab = -1;

    private MeteorTabIsolation() {
    }

    public static boolean isReady() {
        if (!isMeteorLoaded()) {
            return false;
        }
        try {
            return findSystem(getMeteorSystems(), MODULES_CLASS) != null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Captures the outgoing tab, disables its live modules, and prepares the incoming
     * tab's independent snapshot. Call before installing the target Minecraft state.
     */
    public static void beforeSwitch(int sourceTab, int targetTab) {
        if (!isMeteorLoaded() || sourceTab < 0 || targetTab < 0 || sourceTab == targetTab || !isReady()) {
            return;
        }
        if (pendingTargetTab >= 0) {
            throw new IllegalStateException(
                "Meteor tab isolation already has a pending switch from " + pendingSourceTab
                    + " to " + pendingTargetTab
            );
        }

        Snapshot source = captureCurrentState();
        TAB_STATES.put(sourceTab, source);

        Snapshot target = TAB_STATES.get(targetTab);
        if (target == null) {
            // A new Tabby tab starts as a copy of the selected client, then diverges.
            target = source.copy();
            TAB_STATES.put(targetTab, target);
        }

        pendingSourceTab = sourceTab;
        pendingTargetTab = targetTab;
        try {
            disableAllModules();
            LOGGER.debug(
                "Captured Meteor state for tab {} and prepared independent state for tab {}",
                sourceTab,
                targetTab
            );
        } catch (RuntimeException exception) {
            try {
                applyState(source);
            } catch (RuntimeException restoreFailure) {
                exception.addSuppressed(restoreFailure);
            }
            clearPendingSwitch();
            throw exception;
        }
    }

    /**
     * Restores the incoming tab's saved systems after its Minecraft state and screen
     * have been installed.
     */
    public static void afterSwitch(int targetTab) {
        if (pendingTargetTab < 0) {
            return;
        }
        if (targetTab != pendingTargetTab) {
            throw new IllegalStateException(
                "Meteor isolation expected tab " + pendingTargetTab + " but Tabby selected " + targetTab
            );
        }

        Snapshot target = TAB_STATES.get(targetTab);
        if (target == null) {
            throw new IllegalStateException("Meteor state snapshot is missing for tab " + targetTab);
        }

        applyState(target);
        LOGGER.debug("Restored independent Meteor state for tab {}", targetTab);
        clearPendingSwitch();
    }

    /**
     * Called if the Minecraft context switch fails. The source context is already
     * restored when this method runs.
     */
    public static void abortSwitch(int sourceTab) {
        if (pendingTargetTab < 0) {
            return;
        }

        try {
            Snapshot source = TAB_STATES.get(sourceTab);
            if (source != null) {
                applyState(source);
            }
        } finally {
            clearPendingSwitch();
        }
    }

    public static void onTabClosed(int tabId) {
        TAB_STATES.remove(tabId);
        if (pendingSourceTab == tabId || pendingTargetTab == tabId) {
            clearPendingSwitch();
        }
    }

    public static long isolatedBackgroundEventCount() {
        return ISOLATED_BACKGROUND_EVENTS.get();
    }

    public static long backgroundTickCount() {
        return BACKGROUND_TICK_COUNT.get();
    }

    /**
     * Emits Meteor's normal PRE/POST tick events for a background Tabby world.
     * The caller must already be inside TabManager.runIn(tabId), so Minecraft's
     * singleton fields and this tab's Meteor Systems snapshot both belong to tabId.
     */
    public static void tickBackgroundTab(int tabId) {
        if (!isReady()
            || !TabManager.isBackgroundContext()
            || TabManager.executionTabId() != tabId) {
            return;
        }

        TabContext context = TabManager.contexts().stream()
            .filter(tab -> tab.id() == tabId)
            .findFirst()
            .orElse(null);
        if (context == null
            || context.level == null
            || context.player == null
            || context.worldTransitioning
            || context.disconnecting
            || context.networkConnection == null
            || !context.networkConnection.isConnected()) {
            return;
        }

        try {
            Class<?> meteorClient = Class.forName("meteordevelopment.meteorclient.MeteorClient");
            Object eventBus = meteorClient.getField("EVENT_BUS").get(null);
            Class<?> preType = Class.forName("meteordevelopment.meteorclient.events.world.TickEvent$Pre");
            Class<?> postType = Class.forName("meteordevelopment.meteorclient.events.world.TickEvent$Post");
            Object pre = preType.getMethod("get").invoke(null);
            Object post = postType.getMethod("get").invoke(null);
            Method postMethod = eventBus.getClass().getMethod("post", Object.class);

            postMethod.invoke(eventBus, pre);
            postMethod.invoke(eventBus, post);
            BACKGROUND_TICK_COUNT.incrementAndGet();
        } catch (ReflectiveOperationException exception) {
            throw failure("dispatch background tick events for tab " + tabId, exception);
        }
    }

    /**
     * Installs the Meteor Systems snapshot owned by a Tabby context around all work
     * performed for that context. This includes event handlers and direct module/system
     * access made by packet callbacks.
     *
     * Nested work in the same tab reuses its scope. Nested work targeting another tab
     * temporarily installs that tab and restores the previous Meteor state afterwards.
     */
    public static void runInTabContext(int ownerTab, int selectedTab, Runnable task) {
        if (task == null) {
            return;
        }
        if (ownerTab < 0 || selectedTab < 0 || !isMeteorLoaded() || !isReady()) {
            task.run();
            return;
        }

        ArrayDeque<Integer> scopes = TAB_STATE_SCOPES.get();
        int currentScopedTab = scopes.isEmpty() ? -1 : scopes.peek();
        if (currentScopedTab == ownerTab || (currentScopedTab < 0 && ownerTab == selectedTab)) {
            task.run();
            return;
        }

        int restoreTab = currentScopedTab >= 0 ? currentScopedTab : selectedTab;
        Snapshot restoreState = captureCurrentState();
        TAB_STATES.put(restoreTab, restoreState);

        Snapshot targetState = TAB_STATES.get(ownerTab);
        if (targetState == null) {
            targetState = restoreState.copy();
            TAB_STATES.put(ownerTab, targetState);
        }

        scopes.push(ownerTab);
        try {
            applyState(targetState);
            task.run();

            // Save direct API changes and event-driven changes back into this tab.
            TAB_STATES.put(ownerTab, captureCurrentState());
        } catch (RuntimeException | Error exception) {
            try {
                TAB_STATES.put(ownerTab, captureCurrentState());
            } catch (RuntimeException | Error captureFailure) {
                exception.addSuppressed(captureFailure);
            }
            throw exception;
        } finally {
            try {
                applyState(restoreState);
            } catch (RuntimeException | Error restoreFailure) {
                LOGGER.error(
                    "Failed to restore Meteor state for tab {} after running tab {}",
                    restoreTab,
                    ownerTab,
                    restoreFailure
                );
                throw restoreFailure;
            } finally {
                scopes.pop();
                if (scopes.isEmpty()) {
                    TAB_STATE_SCOPES.remove();
                }
            }
        }
    }

    /**
     * Keeps the EventBus wrapper safe for posts not already running inside TabManager.runIn.
     * When a packet scope is already installed, this reuses that same scope.
     */
    public static Object dispatchBackgroundEvent(
        int ownerTab,
        int selectedTab,
        Supplier<Object> dispatch
    ) {
        if (dispatch == null) {
            return null;
        }

        if (ownerTab < 0 || selectedTab < 0 || ownerTab == selectedTab || !isMeteorLoaded() || !isReady()) {
            return dispatch.get();
        }

        Object[] result = new Object[1];
        runInTabContext(ownerTab, selectedTab, () -> result[0] = dispatch.get());
        ISOLATED_BACKGROUND_EVENTS.incrementAndGet();
        return result[0];
    }

    private static boolean isMeteorLoaded() {
        return FabricLoader.getInstance().isModLoaded(METEOR_MOD_ID);
    }

    private static Snapshot captureCurrentState() {
        Map<?, ?> systems = getMeteorSystems();
        Map<String, CompoundTag> serialized = new LinkedHashMap<>();

        for (String systemClass : isolationOrder(systems)) {
            Object system = findSystem(systems, systemClass);
            if (system == null) {
                continue;
            }

            Object tag = invoke(system, "toTag");
            if (tag instanceof CompoundTag compound) {
                serialized.put(systemClass, copy(compound));
            }
        }

        if (!serialized.containsKey(MODULES_CLASS)) {
            throw new IllegalStateException("Meteor Modules system could not be serialized");
        }

        return new Snapshot(serialized, captureActiveModules(systems));
    }

    private static List<String> isolationOrder(Map<?, ?> systems) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        Set<String> available = new HashSet<>();

        for (Object key : systems.keySet()) {
            if (key instanceof Class<?> type) {
                available.add(type.getName());
            }
        }

        if (available.contains(CONFIG_CLASS)) {
            ordered.add(CONFIG_CLASS);
        }
        for (String systemClass : PREFERRED_SYSTEM_ORDER) {
            if (available.contains(systemClass) && !SHARED_SYSTEMS.contains(systemClass)) {
                ordered.add(systemClass);
            }
        }

        // Include serializable addon Systems. A stable class-name order makes restores
        // deterministic, while keeping the central Modules system last for activation.
        Set<String> addonSystems = new TreeSet<>();
        for (String systemClass : available) {
            if (!SHARED_SYSTEMS.contains(systemClass)
                && !MODULES_CLASS.equals(systemClass)
                && !ordered.contains(systemClass)) {
                addonSystems.add(systemClass);
            }
        }
        ordered.addAll(addonSystems);

        if (available.contains(MODULES_CLASS)) {
            ordered.add(MODULES_CLASS);
        }
        return new ArrayList<>(ordered);
    }

    private static Set<String> captureActiveModules(Map<?, ?> systems) {
        Object modules = findSystem(systems, MODULES_CLASS);
        Object allModules = invoke(modules, "getAll");
        if (!(allModules instanceof Iterable<?> iterable)) {
            throw new IllegalStateException("Meteor Modules.getAll() did not return an iterable");
        }

        Set<String> active = new HashSet<>();
        for (Object module : iterable) {
            Object name = readField(module, "name");
            Object isActive = invoke(module, "isActive");
            if (name instanceof String moduleName && Boolean.TRUE.equals(isActive)) {
                active.add(moduleName);
            }
        }
        return active;
    }

    private static void disableAllModules() {
        Object modules = findSystem(getMeteorSystems(), MODULES_CLASS);
        invoke(modules, "disableAll");
    }

    private static void applyState(Snapshot snapshot) {
        Map<?, ?> systems = getMeteorSystems();
        Object modules = findSystem(systems, MODULES_CLASS);
        resetAllModuleSettings(modules);

        // Restore supporting systems before activating modules so callbacks see
        // the target tab's config, friends, waypoints, proxy selection, and HUD.
        for (Map.Entry<String, CompoundTag> entry : snapshot.systems.entrySet()) {
            Object system = findSystem(systems, entry.getKey());
            if (system != null) {
                invokeFromTag(system, copy(entry.getValue()));
            }
        }

        // Modules.toTag intentionally omits modules marked serialize=false. Preserve
        // their active state separately without overriding their settings opt-out.
        restoreActiveModules(modules, snapshot.activeModules);
    }

    private static void resetAllModuleSettings(Object modules) {
        Object allModules = invoke(modules, "getAll");
        if (!(allModules instanceof Iterable<?> iterable)) {
            throw new IllegalStateException("Meteor Modules.getAll() did not return an iterable");
        }

        for (Object module : iterable) {
            Object settings = readField(module, "settings");
            invoke(settings, "reset");
        }
    }

    private static void restoreActiveModules(Object modules, Set<String> activeNames) {
        Object allModules = invoke(modules, "getAll");
        if (!(allModules instanceof Iterable<?> iterable)) {
            throw new IllegalStateException("Meteor Modules.getAll() did not return an iterable");
        }

        for (Object module : iterable) {
            Object nameValue = readField(module, "name");
            if (!(nameValue instanceof String name)) {
                continue;
            }

            boolean shouldBeActive = activeNames.contains(name);
            boolean isActive = Boolean.TRUE.equals(invoke(module, "isActive"));
            if (shouldBeActive != isActive) {
                invoke(module, "toggle");
            }
        }
    }

    private static Map<?, ?> getMeteorSystems() {
        try {
            Class<?> systemsClass = Class.forName(SYSTEMS_CLASS);
            Field systemsField = systemsClass.getDeclaredField("systems");
            systemsField.setAccessible(true);
            Object value = systemsField.get(null);
            if (value instanceof Map<?, ?> map) {
                return map;
            }
            throw new IllegalStateException("Meteor Systems registry is not a map");
        } catch (ReflectiveOperationException exception) {
            throw failure("access Meteor Systems registry", exception);
        }
    }

    private static Object findSystem(Map<?, ?> systems, String className) {
        for (Map.Entry<?, ?> entry : systems.entrySet()) {
            if (entry.getKey() instanceof Class<?> type && type.getName().equals(className)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Object readField(Object target, String fieldName) {
        if (target == null) {
            throw new IllegalStateException("Cannot read Meteor field '" + fieldName + "' from null");
        }
        try {
            return target.getClass().getField(fieldName).get(target);
        } catch (ReflectiveOperationException exception) {
            throw failure("read Meteor field " + fieldName, exception);
        }
    }

    private static Object invoke(Object target, String methodName) {
        if (target == null) {
            throw new IllegalStateException("Cannot call Meteor method '" + methodName + "' on null");
        }
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException exception) {
            throw failure("call Meteor method " + methodName, exception);
        }
    }

    private static void invokeFromTag(Object target, CompoundTag tag) {
        try {
            Method fromTag = null;
            for (Method method : target.getClass().getMethods()) {
                if (method.getName().equals("fromTag")
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(CompoundTag.class)) {
                    fromTag = method;
                    break;
                }
            }
            if (fromTag == null) {
                throw new NoSuchMethodException(target.getClass().getName() + ".fromTag(CompoundTag)");
            }
            fromTag.invoke(target, tag);
        } catch (ReflectiveOperationException exception) {
            throw failure("restore Meteor system " + target.getClass().getName(), exception);
        }
    }

    private static CompoundTag copy(CompoundTag source) {
        return (CompoundTag) source.copy();
    }

    private static RuntimeException failure(String operation, Exception exception) {
        Throwable cause = exception instanceof InvocationTargetException invocation
            ? invocation.getTargetException()
            : exception;
        LOGGER.error("Meteor tab isolation could not {}", operation, cause);
        return new IllegalStateException("Meteor tab isolation could not " + operation, cause);
    }

    private static void clearPendingSwitch() {
        pendingSourceTab = -1;
        pendingTargetTab = -1;
    }

    private static final class Snapshot {
        private final Map<String, CompoundTag> systems;
        private final Set<String> activeModules;

        private Snapshot(Map<String, CompoundTag> systems, Set<String> activeModules) {
            this.systems = systems;
            this.activeModules = activeModules;
        }

        private Snapshot copy() {
            Map<String, CompoundTag> systemsCopy = new LinkedHashMap<>();
            for (Map.Entry<String, CompoundTag> entry : systems.entrySet()) {
                systemsCopy.put(entry.getKey(), MeteorTabIsolation.copy(entry.getValue()));
            }
            return new Snapshot(systemsCopy, new HashSet<>(activeModules));
        }
    }
}
