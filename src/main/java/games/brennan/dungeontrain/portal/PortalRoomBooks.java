package games.brennan.dungeontrain.portal;

import java.util.Locale;

/**
 * Whether the books found in a portal room are all by one author, and how that author is chosen.
 *
 * <p>Community books normally reach a player through a deliberately <i>mixed</i> pool — the selector
 * spreads picks across languages, curation tiers and unread books so no one writer dominates the
 * train. That is right for loot and wrong for a library: an author building a room full of
 * bookshelves had no way to say "these are all somebody's". Each value below is one answer to
 * whose.</p>
 *
 * <h2>Player and Signature are different questions</h2>
 * <p>A signature is free text typed at sign time, so one account can write under several and several
 * accounts can share one. {@link #PLAYER} locks to an account — everything this person wrote,
 * whatever they signed it. {@link #SIGNATURE} locks to the name on the cover — everything signed
 * <i>Faulthurst</i>, whoever held the quill. Neither is a special case of the other, so both are
 * offered.</p>
 *
 * <h2>What "more than ten books" is for</h2>
 * <p>{@link #PLAYER} and {@link #SIGNATURE} draw from authors above a threshold
 * ({@code portalRoomAuthorMinBooks}) because a room wants a catalogue, not a shelf: locking to
 * someone with two books would hand out those two over and over. {@link #SELF} has no threshold —
 * finding your <i>own</i> two books in a room is the point of it — and rotates to a random author
 * for the many players who have written nothing at all.</p>
 *
 * <p>Stored as the fifth segment of the room's {@code mode} tag — see {@link PortalRoomSettings},
 * which owns the encoding.</p>
 */
public enum PortalRoomBooks {

    /** No lock. The default, and what every room did before this existed. */
    OFF("off", "Off"),

    /**
     * Books by whoever picks them up. Resolved per player, so two people in one room each read their
     * own writing — and a player who has written nothing gets a random author rather than nothing.
     */
    SELF("self", "Current Player"),

    /** Books by one randomly chosen account with more than the configured number of approved books. */
    PLAYER("player", "Random Player"),

    /** Books carrying one randomly chosen signature, whoever the accounts behind it are. */
    SIGNATURE("signature", "Random Signature");

    /** What a room with nothing set behaves as: the ordinary mixed pool, exactly as before. */
    public static final PortalRoomBooks DEFAULT = OFF;

    private final String id;
    private final String displayName;

    PortalRoomBooks(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The on-disk / command-line token, e.g. {@code signature}. */
    public String id() {
        return id;
    }

    /** Human-readable label for the editor row. */
    public String displayName() {
        return displayName;
    }

    /** True when the room locks its books to an author at all. */
    public boolean locks() {
        return this != OFF;
    }

    /**
     * True when the lock starts from the player holding the stack rather than from the directory of
     * prolific authors — the one value that can resolve without anybody clearing the threshold.
     */
    public boolean startsFromSelf() {
        return this == SELF;
    }

    /**
     * The directory {@code kind} this value asks the relay for. {@link #SELF} asks for its own entry;
     * {@link #OFF} never asks at all and is treated as {@link #PLAYER} here rather than returning
     * null, so callers can't accidentally build a request for it — they gate on {@link #locks()}.
     */
    public String directoryKind() {
        return this == SIGNATURE ? "signature" : this == SELF ? "self" : "player";
    }

    /**
     * The value named by {@code id}, or {@link #DEFAULT} when it is null, blank or unrecognised.
     *
     * <p>Total, for the same reason {@link PortalRoomMode#parse} and {@link PortalRoomCopies#parse}
     * are: the tag is free text on disk, and a room whose setting was hand-edited to something
     * misspelt should stamp an ordinary room rather than fail the pair's stamp.</p>
     */
    public static PortalRoomBooks parse(String id) {
        if (id == null) return DEFAULT;
        String key = id.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return DEFAULT;
        for (PortalRoomBooks b : values()) {
            if (b.id.equals(key)) return b;
        }
        return DEFAULT;
    }

    /** The value after this one, wrapping — what the editor's Books button steps through. */
    public PortalRoomBooks next() {
        PortalRoomBooks[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
