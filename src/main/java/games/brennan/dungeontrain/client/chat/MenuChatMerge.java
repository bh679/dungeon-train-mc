package games.brennan.dungeontrain.client.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Folds the messages a window-open inbox drain returned into the Discord history for the same visit.
 *
 * <p>Most inbox rows are also in the thread history (a dev reply lands in both), so the drain only
 * needed to feed the "+N" badge. Relay-only notes are not: a whitelist approval, an outdated-build
 * line or a build-review verdict is written straight to the inbox and never posted to Discord, and a
 * player who has never written has no thread at all, so the history fetch answers nothing. Without
 * this merge the drain consumed those rows (advancing the relay's cursor) and the window showed
 * "Couldn't reach Discord." in their place. Pure: a new {@link ChatHistory} every time.</p>
 */
final class MenuChatMerge {

    private MenuChatMerge() {}

    /**
     * {@code history} with any drained {@code inbox} rows it does not already hold appended, in the
     * order the relay returned them. {@code null} when neither side has a message to show — the
     * caller keeps its offline notice. A thread id comes from the history, else the inbox.
     */
    static ChatHistory merge(ChatHistory history, ChatInbox inbox) {
        List<ChatHistory.Message> base = history == null || history.messages() == null
            ? List.of() : history.messages();
        List<ChatHistory.Message> extra = inbox == null || inbox.messages() == null
            ? List.of() : inbox.messages();
        if (base.isEmpty() && extra.isEmpty()) {
            return history;
        }
        Set<String> known = new HashSet<>();
        List<ChatHistory.Message> out = new ArrayList<>(base.size() + extra.size());
        for (ChatHistory.Message m : base) {
            if (m != null) {
                out.add(m);
                if (m.id() != null) known.add(m.id());
            }
        }
        for (ChatHistory.Message m : extra) {
            if (m != null && m.id() != null && known.add(m.id())) {
                out.add(m);
            }
        }
        String threadId = history != null && history.threadId() != null
            ? history.threadId() : inbox != null ? inbox.threadId() : null;
        boolean hasMore = history != null && history.hasMore();
        return new ChatHistory(threadId, List.copyOf(out), hasMore);
    }
}
