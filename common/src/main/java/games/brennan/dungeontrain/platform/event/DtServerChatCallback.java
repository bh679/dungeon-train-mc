package games.brennan.dungeontrain.platform.event;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-neutral form of NeoForge's {@code ServerChatEvent}: fired on the server
 * thread when a player sends a chat message, BEFORE it is broadcast. The
 * NeoForge event is cancellable and its message is mutable; Dungeon Train's only
 * listener is <em>observe-only</em> (it neither cancels nor edits), so this
 * callback is intentionally {@code void} — an exact representation of the current
 * behavior. If a future listener needs to cancel/rewrite, promote this to a
 * boolean-return (cancel) callback and give the bridge a mutable message hook.
 *
 * @param player  the sender
 * @param rawText the raw typed text (matches {@code ServerChatEvent.getRawText()})
 * @param message the decorated broadcast message (matches {@code getMessage()})
 */
@FunctionalInterface
public interface DtServerChatCallback {

    void onChat(ServerPlayer player, String rawText, Component message);
}
