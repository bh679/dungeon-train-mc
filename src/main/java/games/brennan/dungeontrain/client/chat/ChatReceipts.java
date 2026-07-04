package games.brennan.dungeontrain.client.chat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fires the ✅ "delivered to client" receipt ({@link RelayChatClient#markLoaded}) for every <b>human</b>
 * thread message this client has fetched that isn't already marked — so in Discord the dev can tell
 * "their game received it" (✅, fires from the title screen even with the envelope hidden) apart from
 * "they actually read it" (👀, unchanged — fires when the message scrolls into view).
 *
 * <p>Every place messages reach the client funnels through {@link #markLoaded}: the title screen's
 * history fetch and inbox peeks ({@link MenuChatButtonHandler}), and the chat window's history fetch,
 * drain, and live poll ({@link MenuChatScreen}). A session-static id set plus the relay-reported
 * {@code delivered} flag keep re-fetches, re-inits, and polls from ever double-marking; a mark lost to
 * a Discord hiccup self-heals on a later load because {@code delivered} stays false server-side.</p>
 */
final class ChatReceipts {

    /** Ids already marked (or in flight) this session — polls re-see the same rows every few seconds. */
    private static final Set<String> marked = ConcurrentHashMap.newKeySet();

    private ChatReceipts() {}

    /** Mark every not-yet-delivered human message in {@code messages} as loaded by this client. */
    static void markLoaded(UUID uuid, String threadId, List<ChatHistory.Message> messages) {
        if (uuid == null || threadId == null || threadId.isBlank() || messages == null) {
            return;
        }
        for (ChatHistory.Message m : messages) {
            if (m == null || m.id() == null || m.id().isBlank()) {
                continue;
            }
            if (!MenuChatFilter.isHuman(m) || m.delivered()) {
                continue; // only real-person messages earn receipts; already-checked ones stay quiet
            }
            if (marked.add(m.id())) {
                RelayChatClient.markLoaded(uuid, threadId, m.id());
            }
        }
    }
}
