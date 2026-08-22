package games.brennan.dungeontrain.portal;

import java.util.Locale;

/**
 * What the tiles an endless room appends are made of: the room they repeat block for block, a fresh
 * roll of it per tile, or one block the author picked.
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
 * <h2>Single is Endless Open's alone</h2>
 * <p>{@link Kind#SINGLE} replaces an appended tile's floor and ceiling with {@link #blockId},
 * so the plain reads as one material out to the fog rather than as the authored room repeated.
 * It is offered under {@link PortalRoomMode#ENDLESS_OPEN} and nowhere else — see
 * {@link PortalRoomMode#singleCopiesApply}. A Repetition tile is a whole room, and "one block" for
 * a whole room is a solid cube nobody can walk into.</p>
 *
 * <p><b>The base tile is never flattened.</b> Single describes what the tiler <i>appends</i>; the
 * room a player arrives in keeps the floor and ceiling the author built, variant rolls and all. What
 * the setting buys is a hand-made room that opens onto a uniform plain, which is the shape it was
 * asked for — not a way to unbuild the room.</p>
 *
 * <h2>Dynamic is deterministic per copy, on purpose</h2>
 * <p>A copy's variant seed is a pure function of the world seed, the room's name and the copy's
 * position on the grid — so walking back to a copy finds the room you left, with the chests you left
 * in it, rather than a freshly rolled one. Rolling per <i>stamp</i> instead would make the sliding
 * window a loot machine: step far enough for a copy to retire, step back, and its containers refill.
 * Loot still varies between copies, which is what Dynamic is for; it just does not regenerate under a
 * player walking in circles.</p>
 *
 * <p>Stored as the second segment of the room's {@code mode} tag — {@link PortalRoomSettings} owns
 * the encoding, and this class owns its own segment's grammar: {@code exact}, {@code dynamic},
 * {@code single:minecraft:sandstone}.</p>
 *
 * @param kind    what an appended tile is made of
 * @param blockId the block {@link Kind#SINGLE} repeats, as a namespaced id
 */
public record PortalRoomCopies(Kind kind, String blockId) {

    /** What an appended tile is made of. */
    public enum Kind {

        /** Every tile is what the base tile is, block for block — the same variant roll throughout. */
        EXACT("exact", "Exact"),

        /** Each tile rolls its own variants and container contents from the same template. */
        DYNAMIC("dynamic", "Dynamic"),

        /** Each tile's floor and ceiling are one block the author picked. Endless Open only. */
        SINGLE("single", "Single");

        private final String id;
        private final String displayName;

        Kind(String id, String displayName) {
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
         * The kind named by {@code id}, or {@link #EXACT} when it is null, blank or unrecognised.
         *
         * <p>Total, for the same reason {@link PortalRoomMode#parse} is: the tag is free text on disk
         * and a misspelling should stamp a room, not fail a pair.</p>
         */
        public static Kind parse(String id) {
            if (id == null) return EXACT;
            String key = id.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) return EXACT;
            for (Kind k : values()) {
                if (k.id.equals(key)) return k;
            }
            return EXACT;
        }
    }

    /** Separates the kind from the block it repeats inside this setting's own segment. */
    private static final String BLOCK_SEPARATOR = ":";

    /**
     * The block a room set to {@link Kind#SINGLE} repeats before the author has picked one.
     *
     * <p>Something rather than nothing, so the setting is never in a state where choosing it stamps
     * planes of air and drops a player out of the world. Stone is the neutral choice — plainly a
     * placeholder, and plainly solid.</p>
     */
    public static final String DEFAULT_BLOCK = "minecraft:stone";

    /**
     * Longest block id this setting will store.
     *
     * <p>A bound on the <b>tag</b>, not a judgement about block names. The tag rides
     * {@code EditorStatusPacket.MODE_TAG_MAX} as a capped {@code writeUtf}, so an id long enough to
     * push the whole string past that cap would throw on a live server rather than draw a wrong
     * room. Sixty-four clears every block id any real pack ships — {@code minecraft}'s longest is
     * about forty — and anything beyond it reads back as {@link #DEFAULT_BLOCK} rather than as a
     * segment that cannot be sent.</p>
     */
    public static final int BLOCK_ID_MAX = 64;

    /** What a variant with nothing set behaves as: tiles that match the room they repeat. */
    public static final PortalRoomCopies DEFAULT = new PortalRoomCopies(Kind.EXACT, DEFAULT_BLOCK);

    /** Tiles that are the base room block for block — {@link #DEFAULT} under its own name. */
    public static final PortalRoomCopies EXACT = DEFAULT;

    /** Tiles that each roll their own variants and contents. */
    public static final PortalRoomCopies DYNAMIC = new PortalRoomCopies(Kind.DYNAMIC, DEFAULT_BLOCK);

    public PortalRoomCopies {
        if (kind == null) kind = Kind.EXACT;
        blockId = normaliseBlock(blockId);
    }

    /** {@code raw} as a stored block id, or {@link #DEFAULT_BLOCK} when it says nothing. */
    private static String normaliseBlock(String raw) {
        if (raw == null) return DEFAULT_BLOCK;
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || text.length() > BLOCK_ID_MAX) return DEFAULT_BLOCK;
        return text;
    }

    /** This kind at the default block — what the two block-less kinds are always constructed as. */
    public static PortalRoomCopies of(Kind kind) {
        return new PortalRoomCopies(kind, DEFAULT_BLOCK);
    }

    /**
     * Read one stored segment. Total: an unreadable kind falls back to {@link Kind#EXACT} and an
     * absent block to {@link #DEFAULT_BLOCK}, so a hand-edited typo stamps a room rather than
     * failing a pair.
     *
     * <p>Split on the <b>first</b> separator only. A block id carries a colon of its own —
     * {@code single:minecraft:sandstone} is a kind and one id, not a kind and two fragments — and
     * the tag's own separator is {@code /}, which no block id contains, so the two never collide.</p>
     */
    public static PortalRoomCopies parse(String segment) {
        if (segment == null) return DEFAULT;
        String text = segment.trim();
        if (text.isEmpty()) return DEFAULT;

        String[] parts = text.split(BLOCK_SEPARATOR, 2);
        return new PortalRoomCopies(
            Kind.parse(parts[0]),
            parts.length > 1 ? parts[1] : DEFAULT_BLOCK);
    }

    /**
     * The segment to store.
     *
     * <p>The block is written only under {@link Kind#SINGLE}, the one kind that reads it — so a room
     * that simply repeats round-trips as the bare {@code exact} or {@code dynamic} it always was, and
     * every tag written before Single existed is re-written unchanged.</p>
     */
    public String id() {
        return kind == Kind.SINGLE ? kind.id() + BLOCK_SEPARATOR + blockId : kind.id();
    }

    /** Human-readable label for the editor row. The block has a row of its own. */
    public String displayName() {
        return kind.displayName();
    }

    /** True when appended tiles are floor and ceiling planes of {@link #blockId}. */
    public boolean repeatsOneBlock() {
        return kind == Kind.SINGLE;
    }

    /**
     * The kind after this one, wrapping, keeping the block.
     *
     * <p>{@code singleAllowed} is the mode's answer, not this setting's: {@link Kind#SINGLE} is
     * skipped where the walls cannot use it, so the editor's one cycling button steps through
     * exactly the options the room in front of the author actually has. Asked here rather than
     * filtered by the caller because the wrap has to close over the shortened list — stepping off
     * the end of a two-option cycle must land back at the first, not on the option that was
     * skipped.</p>
     */
    public PortalRoomCopies next(boolean singleAllowed) {
        Kind[] all = Kind.values();
        Kind stepped = kind;
        do {
            stepped = all[(stepped.ordinal() + 1) % all.length];
        } while (stepped == Kind.SINGLE && !singleAllowed);
        return new PortalRoomCopies(stepped, blockId);
    }

    public PortalRoomCopies withKind(Kind newKind) {
        return new PortalRoomCopies(newKind, blockId);
    }

    public PortalRoomCopies withBlock(String newBlockId) {
        return new PortalRoomCopies(kind, newBlockId);
    }
}
