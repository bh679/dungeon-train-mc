package games.brennan.dungeontrain.editor;

import java.util.Locale;

/**
 * How a variant cell rolls across the copies of a repeating dimensional carriage room.
 *
 * <p>The room already answers this for every cell it holds: {@code PortalRoomCopies.Kind#EXACT}
 * repeats one roll block for block down the hall, {@code DYNAMIC} rolls each copy afresh. This is
 * the per-cell override — one cell that varies in a room that repeats, or one that holds still in
 * a room that varies — and {@link #DEFAULT} is the cell simply not overriding anything.</p>
 *
 * <h2>Why Default is a state and not the absence of one</h2>
 * <p>A two-state toggle was tried first and read wrong at both ends: its "off" was labelled Exact
 * while actually meaning "whatever the room says", so in a Dynamic room the setting claimed the
 * cell repeated when it did not, and there was no way to ask for a cell that really did.</p>
 *
 * <h2>Independent of {@link VariantCopyScope}</h2>
 * <p>This says <i>how the cell rolls</i>; the scope says <i>which tiles it is in at all</i>. They
 * compose — a cell can apply only in the copies and hold one roll across all of them.</p>
 *
 * <p>Stored per cell as {@code "roll"} inside the sidecar's cell object, omitted entirely for
 * {@link #DEFAULT} so a cell that never touched the setting round-trips byte-identical.</p>
 */
public enum VariantCopyRoll {

    /** Whatever the room's Copies setting says. What every cell did before the override existed. */
    DEFAULT("default", "Default"),

    /** One roll, shared by every copy — even in a room whose other cells reroll per copy. */
    EXACT("exact", "Exact"),

    /** A fresh roll in every copy — even in a room whose other cells repeat one roll. */
    VARY("vary", "Vary");

    private final String id;
    private final String displayName;

    VariantCopyRoll(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The on-disk key. */
    public String id() {
        return id;
    }

    /** The word the menu's toolbar cell shows. */
    public String displayName() {
        return displayName;
    }

    /** True for the default — the one value that is never written to disk. */
    public boolean isDefault() {
        return this == DEFAULT;
    }

    /**
     * The roll named by {@code id}, or {@link #DEFAULT} when it is null, blank or unrecognised.
     *
     * <p>Total, like every other sidecar value parser here: a hand-edited typo should stamp the
     * room the way it always did rather than fail the file.</p>
     */
    public static VariantCopyRoll parse(String id) {
        if (id == null) return DEFAULT;
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (VariantCopyRoll r : values()) {
            if (r.id.equals(key)) return r;
        }
        return DEFAULT;
    }

    /** The roll at {@code ordinal}, or {@link #DEFAULT} when it is out of range — the wire's read side. */
    public static VariantCopyRoll fromOrdinal(int ordinal) {
        VariantCopyRoll[] all = values();
        return ordinal < 0 || ordinal >= all.length ? DEFAULT : all[ordinal];
    }

    /** The next roll, wrapping — what the toolbar cell's click does. */
    public VariantCopyRoll next() {
        VariantCopyRoll[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
