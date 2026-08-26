package games.brennan.dungeontrain.client;

/**
 * Client-side mirror of this player's book-upload pause — the last hop of a window that starts at the
 * relay ({@code booksuspensions.js}), is held server-side by
 * {@link games.brennan.dungeontrain.narrative.BookUploadSuspensions}, and arrives here as a
 * {@link games.brennan.dungeontrain.net.BookSuspensionSyncPacket}.
 *
 * <p>Its only reader is the signing screen ({@code BookEditScreenSuspensionMixin}), which greys and
 * reddens the Sign button while a window is open. The deadline is computed from the packet's DURATION
 * against the client's own clock, so the two machines never have to agree on the time — and it expires
 * itself, so the button frees up the moment the window lapses with the screen still open.</p>
 *
 * <p>Advisory only: the server refuses a suspended sign regardless of what the client believes.</p>
 */
public final class ClientBookSuspension {

    private ClientBookSuspension() {}

    private static volatile long untilMs;
    private static volatile int strikes;

    /** Apply a synced window. {@code remainingSec <= 0} clears it. */
    public static void set(long remainingSec, int strikeCount) {
        untilMs = remainingSec <= 0L ? 0L : System.currentTimeMillis() + remainingSec * 1000L;
        strikes = Math.max(0, strikeCount);
    }

    /** True while this client believes its player's uploads are paused. */
    public static boolean isSuspended() {
        return remainingMs() > 0L;
    }

    /** Milliseconds left, or 0 when free. */
    public static long remainingMs() {
        long until = untilMs;
        if (until <= 0L) return 0L;
        long left = until - System.currentTimeMillis();
        if (left <= 0L) {
            untilMs = 0L;
            return 0L;
        }
        return left;
    }

    /** Whole seconds left, rounded UP so a sub-second remainder never reads as "0 seconds". */
    public static long remainingSec() {
        long ms = remainingMs();
        return ms <= 0L ? 0L : (ms + 999L) / 1000L;
    }

    /** How many duplicate uploads this player has been caught at — for the message, not for decisions. */
    public static int strikes() {
        return strikes;
    }

    /** Test seam / disconnect — forget the window. */
    public static void clear() {
        untilMs = 0L;
        strikes = 0;
    }
}
