package games.brennan.dungeontrain.client.chat;

import java.util.List;

/**
 * Immutable, panel-ready model of a player's Discord thread, as returned by the relay's
 * {@code GET /<CAP>/chat/history} endpoint (see dp-relay {@code chat.js}). Parsed from JSON by
 * {@link RelayChatClient}; never holds raw Discord objects.
 *
 * <p>Messages are ordered oldest→newest. {@code seen} reflects whether the bot has already added the
 * 👀 reaction (so the panel doesn't re-mark it). Feedback-survey answers arrive as {@link Embed}s and
 * bug-report submissions as {@link Attachment}s, since both already live in the same thread.</p>
 */
public record ChatHistory(String threadId, List<Message> messages, boolean hasMore) {

    public record Message(
            String id,
            String authorId,
            String authorName,
            boolean isBot,
            boolean isWebhook,
            String content,
            List<Embed> embeds,
            List<Attachment> attachments,
            String timestamp,
            boolean seen) {

        /** A real Discord-side message (dev/community/bot) — the kind the panel marks 👀 when seen. */
        public boolean isInbound() {
            return !isWebhook;
        }

        public boolean hasEmbeds() {
            return embeds != null && !embeds.isEmpty();
        }

        public boolean hasAttachments() {
            return attachments != null && !attachments.isEmpty();
        }
    }

    public record Embed(String title, String description, Integer color, List<Field> fields) {}

    public record Field(String name, String value) {}

    public record Attachment(String filename, Long size, String contentType, String url) {}
}
