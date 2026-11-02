package dev.tabby.tab;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Installs one tab's snapshot around packet work and restores the previous
 * snapshot even when nested packet handlers or exceptions occur.
 *
 * This is the only owner of the execution-context ThreadLocal. It deliberately
 * does not own tab lifecycle or connection routing.
 */
final class TabContextExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("Tabby");
    private static final ThreadLocal<Integer> EXECUTION_CONTEXT = new ThreadLocal<>();

    private TabContextExecutor() {
    }

    static Integer executionId() {
        return EXECUTION_CONTEXT.get();
    }

    static int currentContextId(int activeId) {
        Integer contextId = EXECUTION_CONTEXT.get();
        return contextId == null ? activeId : contextId;
    }

    static boolean isBackgroundContext(int activeId) {
        Integer contextId = EXECUTION_CONTEXT.get();
        return contextId != null && contextId != activeId;
    }

    static boolean isContextInstalled(boolean clientInitialized, int activeId, int tabId) {
        return clientInitialized && currentContextId(activeId) == tabId;
    }

    static void clearCurrentThread() {
        EXECUTION_CONTEXT.remove();
    }

    static void runIn(
        Minecraft client,
        int activeId,
        Map<Integer, TabContext> contexts,
        int tabId,
        Runnable task
    ) {
        if (client == null || task == null) {
            if (task != null) {
                task.run();
            }
            return;
        }

        int previousId = currentContextId(activeId);
        if (previousId == tabId) {
            // Mark even active-tab work: setLevel/login handlers can trigger nested client ticks.
            Integer previousExecutionId = EXECUTION_CONTEXT.get();
            if (previousExecutionId != null) {
                task.run();
                return;
            }

            EXECUTION_CONTEXT.set(tabId);
            try {
                task.run();
            } catch (RuntimeException | Error exception) {
                LOGGER.debug("Packet task failed in active tab {}", tabId, exception);
                throw exception;
            } finally {
                EXECUTION_CONTEXT.remove();
            }
            return;
        }

        TabContext previous = contexts.get(previousId);
        TabContext target = contexts.get(tabId);
        if (target == null) {
            LOGGER.debug("Ignoring task for unknown tab {}", tabId);
            return;
        }

        Integer previousExecutionId = EXECUTION_CONTEXT.get();
        TabContext previousState = previous;
        if (previous != null) {
            if (previousExecutionId == null) {
                // At the outermost packet boundary the visible context is stable.
                previous.captureFrom(client);
            } else {
                // A nested runIn must preserve transient globals without publishing them.
                previousState = TabContext.capture(client, previousId, previous.title());
            }
        }

        try {
            EXECUTION_CONTEXT.set(tabId);
            // Background packet handlers must not replace the visible Screen.
            target.applyTo(client, false);
            task.run();
        } catch (RuntimeException | Error exception) {
            LOGGER.debug("Packet task failed in tab {}", tabId, exception);
            throw exception;
        } finally {
            try {
                target.captureFrom(client);
            } finally {
                try {
                    if (previousState != null) {
                        previousState.applyTo(client, false);
                    }
                } finally {
                    if (previousExecutionId == null) {
                        EXECUTION_CONTEXT.remove();
                    } else {
                        EXECUTION_CONTEXT.set(previousExecutionId);
                    }
                }
            }
        }
    }
}
