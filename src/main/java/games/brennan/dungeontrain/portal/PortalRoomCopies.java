package games.brennan.dungeontrain.portal;

import java.util.Locale;

/**
 * Whether the tiles an endless room appends are identical to the room they repeat, or each rolled
 * afresh from its block-variant sidecar.
 *
 * <p>A sub-mode rather than a mode of its own: it only means anything while the walls are set to one
 * of the two endless modes, and it says nothing about what happens at them.</p>
 *
 * <h2>What it varies depends on what the tile carries</h2>
 * <p>Under {@link PortalRoomMode#ENDLESS_REPETITION} a tile is the whole room, so Dynamic varies
 * everything in it — blocks, furnishing and chest contents alike. Under
 * {@link PortalRoomMode#ENDLESS_OPEN} a tile is the floor and the ceiling and nothing between them
 * ({@code PortalRoomTiler.writeMaskFor}), so Dynamic varies those two planes and there is no
 * per-copy loot for it to vary — the interior is never written in the first place. Exact repeats
 * one roll of whichever of the two it is.</p>
 *
 * <h2>Dynamic is deterministic per copy, on purpose</h2>
 * <p>A copy's variant seed is a pure function of the world seed, the room's name and the copy's
 * position on the grid — so walking back to a copy finds the room you left, with the chests you left
 * in it, rather than a freshly rolled one. Rolling per <i>stamp</i> instead would make the sliding
 * window a loot machine: step far enough for a copy to retire, step back, and its containers refill.
 * Loot still varies between copies, which is what Dynamic is for; it just does not regenerate under a
 * player walking in circles.</p>
 */
public enum PortalRoomCopies {

    /** Every tile is what the base tile is, block for block — the same variant roll throughout. */
    EXACT("exact", "Exact"),

    /** Each tile rolls its own variants and container contents from the same template. */
    DYNAMIC("dynamic", "Dynamic");

    /** What a variant with nothing set behaves as: tiles that match the room they repeat. */
    public static final PortalRoomCopies DEFAULT = EXACT;

    private final String id;
    private final String displayName;

    PortalRoomCopies(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The on-disk / command-line token. */
    public String id() {
        return id;
    }

    /** Human-readable label for the editor row. */
    public String displayName() {
        return displayName;
    }

    /**
     * The sub-mode named by {@code id}, or {@link #DEFAULT} when it is null, blank or unrecognised.
     *
     * <p>Total, for the same reason {@link PortalRoomMode#parse} is: the tag is free text on disk and
     * a misspelling should stamp a room, not fail a pair.</p>
     */
    public static PortalRoomCopies parse(String id) {
        if (id == null) return DEFAULT;
        String key = id.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return DEFAULT;
        for (PortalRoomCopies c : values()) {
            if (c.id.equals(key)) return c;
        }
        return DEFAULT;
    }

    /** The sub-mode after this one, wrapping — what the editor's Copies button steps through. */
    public PortalRoomCopies next() {
        PortalRoomCopies[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
