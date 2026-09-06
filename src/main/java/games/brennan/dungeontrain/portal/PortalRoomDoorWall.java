package games.brennan.dungeontrain.portal;

import java.util.Locale;

/**
 * Whether the portal room's own walls are included in the tiling — the tiler's to reshape, or the
 * author's to keep.
 *
 * <h2>The two answers</h2>
 * <p>{@link #SEALED} is what an endless room has always done: the wall between two copies is
 * <b>carved away</b> ({@code PortalRoomTiler.carveSeam}), so a grid of rooms reads as one continuous
 * hall, and a corridor mouth's plane belongs to the mouth rather than to the copy standing at it.
 * Anything the author put on a wall goes with it — which is why a marked wall does not survive into
 * the copies, while the block a torch hangs on does: the carve skips a cell whose front is not
 * air.</p>
 *
 * <p>{@link #REPEATED} makes the walls part of what repeats. No seam is carved, no outer face is
 * closed, and the mouth plane is stamped by the copy that owns it — so every tile is the room the
 * author built, and players pass between copies through the openings the author drew.</p>
 *
 * <h2>The mouth plane is the same question asked of one column</h2>
 *
 * <h2>The plane this is about</h2>
 * <p>A corridor's seal ring fills the room's whole cross-section one column outside the base room box
 * ({@link PortalCorridorMask}). Under {@link PortalRoomMode#ENDLESS_REPETITION} that column <b>is</b>
 * the wall plane of the copy standing at tile {@code (+1, 0)} or {@code (-1, 0)}: the tiles are laid
 * one room-length apart, so the exit mouth seals at the minimum X of the tile ahead and the entry
 * mouth at the maximum X of the tile behind.</p>
 *
 * <p>Under {@link #SEALED} that plane belongs to the mouth. The copy is masked off it and what a
 * player sees is the ring {@code PortalCarriageBuilder.sealCorridorMouth} laid: the room's wall
 * mirrored from the <i>opposite</i> end column and flattened by
 * {@link PortalRoomTiler#usableAsFill}, so stairs, slabs, panes and authored openings do not survive
 * it and — under {@link PortalRoomCopies.Kind#DYNAMIC} — the blocks are the base room's roll rather
 * than that copy's.</p>
 *
 * <p>Under {@link #REPEATED} the plane belongs to the copy. It is stamped through a seal-less mask
 * and lays its own wall there, detail and all — and whatever the author left as air stays air. Until
 * that copy lands, the mouth carries the base room's wall on where it has one and shows basement
 * rock where it does not ({@code PortalCarriageBuilder.sealCorridorMouth}, wall-only).</p>
 *
 * <h2>Why it is a setting rather than simply the right answer</h2>
 * <p>The two read differently and an author may want either. A sealed mouth is a plain wall with a
 * door in it — the way back reads as a way back, and the endless room resumes past it. A repeated one
 * makes the room genuinely continuous through the carriage, which is what a hall or a library wants
 * and what a corridor-as-landmark does not.</p>
 *
 * <h2>Kept is the default, and that was a migration</h2>
 * <p>{@link #SEALED} was the default from the day this existed, on the argument that a setting whose
 * default changes what is standing in somebody's world is not a setting but a migration. On
 * 2026-09-05 Brennan chose the migration: every room tiles exactly as its author built it unless the
 * author asks for the merged look, and the shipped endless rooms with no seventh segment change
 * with it. What tipped it was an open-sided room — {@code bigger}, {@code distantenemies} — whose
 * corridor mouths came out as a wall of the room's own floor block that nobody drew, and whose
 * frontier tiles grew mirrored blocks that vanished a tick later. Under {@link #REPEATED} the tiler
 * now invents nothing at all: no seam, no closed face, and a mouth plane that carries the room's
 * wall on where there is one and leaves the rock where there is not.</p>
 *
 * <p>Stored as the seventh segment of the room's {@code mode} tag — see {@link PortalRoomSettings},
 * which owns the encoding.</p>
 */
public enum PortalRoomDoorWall {

    /**
     * The walls are the tiler's. Seams between copies are carved open, outer faces are closed, and a
     * corridor mouth's plane belongs to the mouth. What every room did before the setting existed;
     * opt-in since the default flipped.
     */
    SEALED("sealed", "Merged"),

    /**
     * The walls are the room's. Every tile keeps them exactly as the author built them. The
     * default.
     */
    REPEATED("repeated", "Kept");

    /** What a variant with no door-wall segment — or an unreadable one — behaves as. */
    public static final PortalRoomDoorWall DEFAULT = REPEATED;

    private final String id;
    private final String displayName;

    PortalRoomDoorWall(String id, String displayName) {
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
     * The value named by {@code segment}, or {@link #DEFAULT} when it is null, blank or unrecognised.
     *
     * <p>Total, for the same reason {@link PortalRoomSky#parse} and {@link PortalRoomMode#parse} are:
     * the tag is free text on disk, and a misspelling should stamp a room rather than fail the
     * pair.</p>
     */
    public static PortalRoomDoorWall parse(String segment) {
        if (segment == null) return DEFAULT;
        String key = segment.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return DEFAULT;
        for (PortalRoomDoorWall value : values()) {
            if (value.id.equals(key)) return value;
        }
        return DEFAULT;
    }

    /**
     * True when the room's walls are part of the tiling: no seam carved, no outer face closed, and a
     * copy writes its own wall into a corridor mouth's plane.
     */
    public boolean repeats() {
        return this == REPEATED;
    }

    /** The value after this one, wrapping — what the editor's Room Walls button steps through. */
    public PortalRoomDoorWall next() {
        PortalRoomDoorWall[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
