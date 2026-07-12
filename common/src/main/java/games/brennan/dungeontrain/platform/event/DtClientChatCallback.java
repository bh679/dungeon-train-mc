package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's {@code ClientChatEvent} (client game bus).
 * Fires on the client thread when the local player submits a chat line, before it
 * is sent. NeoForge's event is cancellable with a mutable message, but DT's sole
 * handler only observes (records a timestamp), so this is a {@code void}
 * passthrough that ignores the message and never cancels.
 */
@FunctionalInterface
public interface DtClientChatCallback {

    void onClientChat();
}
