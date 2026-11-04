package dev.tabby.tab;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tracks unread chat lines per background tab. Chat is recorded while its owning
 * tab context is installed by TabContextExecutor, so messages cannot leak between tabs.
 */
final class TabUnreadMessageManager {
    private static final int MAX_COUNT = 999;
    private static final Map<Integer, MessageCounts> COUNTS = new HashMap<>();
    private static String cachedMentionName;
    private static Pattern cachedMentionPattern;

    private TabUnreadMessageManager() {
    }

    static void onChatMessage(Component message) {
        if (message == null || !TabbyConfig.showUnreadMessages()) {
            return;
        }

        int tabId = TabManager.currentContextId();
        if (tabId < 0 || tabId == TabRegistry.activeId()) {
            return;
        }

        TabContext context = TabRegistry.get(tabId);
        if (context == null) {
            return;
        }

        MessageCounts counts = COUNTS.computeIfAbsent(tabId, ignored -> new MessageCounts());
        counts.messages = Math.min(MAX_COUNT, counts.messages + 1);

        String username = context.playerName;
        if (username == null || username.isBlank()) {
            Minecraft client = TabManager.clientInstance();
            username = client == null ? null : client.getUser().getName();
        }
        String text = message.getString();
        if (username != null && !username.isBlank() && containsMention(text, username)) {
            counts.mentions = Math.min(MAX_COUNT, counts.mentions + 1);
        }
    }

    private static boolean containsMention(String message, String username) {
        if (!username.equals(cachedMentionName) || cachedMentionPattern == null) {
            cachedMentionName = username;
            cachedMentionPattern = Pattern.compile(
                "(?iu)(?<![\\p{L}\\p{N}_])@?" + Pattern.quote(username) + "(?![\\p{L}\\p{N}_])"
            );
        }
        return cachedMentionPattern.matcher(message).find();
    }

    static String badge(int tabId) {
        if (!TabbyConfig.showUnreadMessages()) {
            return "";
        }
        MessageCounts counts = COUNTS.get(tabId);
        if (counts == null || counts.messages == 0) {
            return "";
        }

        String result = "●" + compact(counts.messages);
        if (counts.mentions > 0) {
            result += " @" + compact(counts.mentions);
        }
        return result;
    }

    static boolean hasMention(int tabId) {
        MessageCounts counts = COUNTS.get(tabId);
        return counts != null && counts.mentions > 0;
    }

    static int unreadMessages(int tabId) {
        MessageCounts counts = COUNTS.get(tabId);
        return counts == null ? 0 : counts.messages;
    }

    static int unreadMentions(int tabId) {
        MessageCounts counts = COUNTS.get(tabId);
        return counts == null ? 0 : counts.mentions;
    }

    static void markRead(int tabId) {
        COUNTS.remove(tabId);
    }

    static void removeTab(int tabId) {
        COUNTS.remove(tabId);
    }

    static void clear() {
        COUNTS.clear();
        cachedMentionName = null;
        cachedMentionPattern = null;
    }

    private static String compact(int count) {
        return count >= 100 ? "99+" : Integer.toString(count);
    }

    private static final class MessageCounts {
        private int messages;
        private int mentions;
    }
}
