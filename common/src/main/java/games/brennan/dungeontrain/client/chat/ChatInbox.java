package games.brennan.dungeontrain.client.chat;

import java.util.List;

/**
 * Immutable result of draining a player's offline inbox from the relay's
 * {@code GET /<CAP>/chat/inbox?uuid=} endpoint (see dp-relay {@code inbox.js}). Parsed by
 * {@link RelayChatClient}.
 *
 * <p>The inbox holds the real-person thread replies that arrived while the player was away from the menu;
 * draining returns the ones not yet delivered and advances a server-side cursor, so {@link #unread} is the
 * count of <em>new</em> messages since the last open — what the panel surfaces as an unread badge.
 * {@code messages} reuses {@link ChatHistory.Message} (inbox rows carry no embeds/attachments); the
 * Phase&nbsp;3 menu uses only {@link #unread}, but the list is parsed so the deferred in-game delivery
 * path can consume the same shape.</p>
 */
public record ChatInbox(String threadId, int unread, List<ChatHistory.Message> messages) {
}
