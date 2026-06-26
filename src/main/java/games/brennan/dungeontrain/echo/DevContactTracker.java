package games.brennan.dungeontrain.echo;

/**
 * Remembers when the server last saw "communication with the dev" — either a player pinging
 * {@code @dev}/{@code @brennanhatton} in chat, or a relayed Discord message authored by the dev
 * (delivered via {@link games.brennan.dungeontrain.compat.DiscordInboundBridge}).
 *
 * <p>Used by {@link RemoteEchoEncounters} as a privacy guard: when the player chats near a remote
 * echo within {@link #DEV_COMM_WINDOW_MILLIS} of dev contact, the echo story withholds the chat
 * contents (notes only that they spoke) rather than quoting a possibly-private exchange.</p>
 *
 * <p>Wall-clock (not game ticks) because the window is a real-world "last 5 minutes" and one source
 * is an off-thread Discord callback. {@link #mark} runs on a network thread while {@link #isRecent}
 * runs on the server thread, so the timestamp is {@code volatile} — a single primitive write/read
 * needs no further synchronisation.</p>
 */
final class DevContactTracker {

    /** How long after dev contact chat contents stay withheld from the echo story. */
    static final long DEV_COMM_WINDOW_MILLIS = 5 * 60_000L;

    private static volatile long lastMillis = Long.MIN_VALUE;

    private DevContactTracker() {}

    /** Stamp dev contact at {@code nowMillis} (System.currentTimeMillis()). */
    static void mark(long nowMillis) {
        lastMillis = nowMillis;
    }

    /** True if dev contact occurred within {@code windowMillis} before {@code nowMillis}. */
    static boolean isRecent(long nowMillis, long windowMillis) {
        long last = lastMillis;
        return last != Long.MIN_VALUE && nowMillis - last <= windowMillis;
    }
}
