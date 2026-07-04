package games.brennan.dungeontrain.client.chat;

import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The shared live-receive heartbeat: a self-throttled, single-flight peek of the relay inbox
 * ({@link RelayChatClient#peekInbox} — non-destructive, the drain cursor is untouched). Both menu-chat
 * surfaces drive it from their render/tick loops — the title-screen envelope button (to update the
 * unread popup) and the open {@link MenuChatScreen} (to stream replies into the list) — and it is cheap:
 * the relay serves peeks from its in-memory, gateway-fed inbox, no Discord API call. Only one surface is
 * active at a time, so the shared throttle keeps the cadence at one request per {@link #INTERVAL_MS}.
 */
final class MenuChatLivePoll {

    private static final long INTERVAL_MS = 3500;

    private static long lastPollMs;
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);

    private MenuChatLivePoll() {}

    /**
     * Peek the inbox if the throttle allows, delivering a non-null {@link ChatInbox} to {@code onInbox}
     * on the render thread. No-ops (silently) when consent is missing, a poll is already in flight, the
     * interval hasn't elapsed, or the peek fails — the next loop simply tries again.
     */
    static void poll(UUID uuid, Consumer<ChatInbox> onInbox) {
        if (uuid == null || !RelayChatClient.canConnect()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPollMs < INTERVAL_MS || !inFlight.compareAndSet(false, true)) {
            return; // throttled, or a poll is already in flight
        }
        lastPollMs = now;
        Minecraft mc = Minecraft.getInstance();
        RelayChatClient.peekInbox(uuid).thenAcceptAsync(inbox -> {
            inFlight.set(false);
            if (inbox != null) {
                onInbox.accept(inbox);
            }
        }, mc).exceptionally(t -> {
            inFlight.set(false);
            return null;
        });
    }
}
