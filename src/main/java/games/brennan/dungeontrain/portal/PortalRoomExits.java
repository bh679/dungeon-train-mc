package games.brennan.dungeontrain.portal;

import java.util.Locale;

/**
 * How many extra ways back to the train an endless portal room scatters through its copies, and how
 * far apart they are.
 *
 * <p>An endless room repeats forever but used to have exactly <b>one</b> way out: the single pair of
 * twin corridors standing at the base tile. Walk five rooms out and the only option is to walk five
 * rooms back — and the sliding window ({@link PortalRoomTiling}) is what makes that distance real
 * rather than a bluff. This setting stamps further copies of those corridors out in the tiling, each
 * of which takes a player back to the same carriage the original does; see {@link PortalExitSites}
 * for where they land and {@link PortalExitTransit} for how they carry.</p>
 *
 * <h2>Two controls, one setting</h2>
 * <p>{@link Kind} says whether copies are laid on a regular lattice, rolled per tile, or not laid at
 * all; {@link #every} is the {@code X} both readings are measured in. They travel together because
 * neither means anything alone — a lattice with no spacing is not a lattice, and a spacing with
 * nothing to space is noise in the tag.</p>
 *
 * <h2>The default depends on the walls</h2>
 * <p>{@link PortalRoomMode#ENDLESS_REPETITION} defaults to {@link Kind#ON}: it is a hall of identical
 * rooms, which is exactly the shape a player gets lost in, so extra doors are the kind thing. {@link
 * PortalRoomMode#ENDLESS_OPEN} defaults to {@link Kind#OFF}: it is an open plain with sightlines
 * across it, so the way back stays visible and a corridor standing in the middle of it is furniture
 * nobody asked for. See {@link PortalRoomMode#defaultExits}.</p>
 *
 * <p>Stored as the fourth segment of the room's {@code mode} tag — {@link PortalRoomSettings} owns
 * the encoding, and this class owns its own segment's grammar: {@code on}, {@code random:12},
 * {@code off}.</p>
 *
 * @param kind        how copies are chosen
 * @param every       the {@code X} in "every X tiles" / "one tile in X"
 * @param sealChance  how often a {@link Kind#RANDOM} room walls off the base pair's exit, 0..10
 */
public record PortalRoomExits(Kind kind, int every, int sealChance) {

    /** How the extra corridors are chosen. */
    public enum Kind {

        /** A regular lattice: every {@code every} tiles on both axes gets an entry <i>and</i> an exit. */
        ON("on", "On"),

        /** One tile in {@code every} gets a single corridor — see {@link #ENTRY_SHARE}. */
        RANDOM("random", "Random"),

        /** No extra corridors: the one pair at the base tile, as it has always been. */
        OFF("off", "Off");

        private final String id;
        private final String displayName;

        Kind(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        /** The on-disk / command-line token, e.g. {@code random}. */
        public String id() {
            return id;
        }

        /** Human-readable label for the editor row. */
        public String displayName() {
            return displayName;
        }

        /** True when this lays any copies at all. */
        public boolean lays() {
            return this != OFF;
        }

        /**
         * The kind named by {@code id}, or {@link #ON} when it is null, blank or unrecognised.
         *
         * <p>Total, for the same reason {@link PortalRoomMode#parse} is: the tag is free text on disk,
         * and a room whose setting was hand-edited to something misspelt should stamp a room rather
         * than fail the whole pair's stamp and leave a player walking into an unbuilt corridor.</p>
         *
         * <p>Falling back to {@code ON} rather than to a mode-dependent default is deliberate — this
         * method is handed a segment, not a room, so it has no mode to ask. {@link
         * PortalRoomSettings#parse} is where an <i>absent</i> segment becomes {@link
         * PortalRoomMode#defaultExits}; a present-but-unreadable one is a different case and lands
         * here.</p>
         */
        public static Kind parse(String id) {
            if (id == null) return ON;
            String key = id.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) return ON;
            for (Kind k : values()) {
                if (k.id.equals(key)) return k;
            }
            return ON;
        }

        /** The kind after this one, wrapping — what the editor's Exits button steps through. */
        public Kind next() {
            Kind[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    /** Separates the kind from its spacing inside this setting's own segment. */
    private static final String SPACING_SEPARATOR = ":";

    /** The {@code X} a room that names a kind but no spacing gets. */
    public static final int DEFAULT_EVERY = 8;

    /**
     * Tightest spacing an author may ask for.
     *
     * <p>Two, not one. At one, {@link Kind#ON} puts a set of corridors in <i>every</i> tile, and a
     * corridor is longer than a room at the default dims (13 against 11) — so consecutive sets would
     * overlap each other's volume and the room between them would be corridor end to end. Two is the
     * tightest spacing at which a copy still stands in a room rather than replacing it.</p>
     */
    public static final int MIN_EVERY = 2;

    /**
     * Loosest spacing worth storing.
     *
     * <p>Well past the point of usefulness on purpose: the window only ever holds
     * {@link PortalRoomTiling#MAX_RADIUS} rooms in each direction, so a lattice spaced beyond a few
     * dozen tiles is one a player would have to walk a very long way to meet. It is a bound on the
     * number rather than a judgement about it — an author who wants copies to be a rare event should
     * be able to say so.</p>
     */
    public static final int MAX_EVERY = 64;

    /** Odds that a {@link Kind#RANDOM} copy is an entry rather than an exit, as a percentage. */
    public static final int ENTRY_SHARE = 25;

    /**
     * The top of the sealed-exit scale: {@code 0} never, {@code SEAL_ALWAYS} always.
     *
     * <p>Ten rather than a hundred because it is a dial an author turns, not a probability they
     * calculate — "seven out of ten portals" is a sentence, "70%" invites tuning it to 68.</p>
     */
    public static final int SEAL_ALWAYS = 10;

    /** What a room says about sealing when it says nothing: never. */
    public static final int SEAL_NEVER = 0;

    /** What every room behaved as before this existed: no extra corridors. */
    public static final PortalRoomExits OFF = new PortalRoomExits(Kind.OFF, DEFAULT_EVERY, SEAL_NEVER);

    /** The lattice at its default spacing — what an Endless Repetition room gets when it says nothing. */
    public static final PortalRoomExits ON = new PortalRoomExits(Kind.ON, DEFAULT_EVERY, SEAL_NEVER);

    public PortalRoomExits {
        if (kind == null) kind = Kind.ON;
        every = clampEvery(every);
        sealChance = clampSeal(sealChance);
    }

    /** The pair this record was before the sealed exit existed — never seals. */
    public PortalRoomExits(Kind kind, int every) {
        this(kind, every, SEAL_NEVER);
    }

    /** {@code every} brought inside {@link #MIN_EVERY}..{@link #MAX_EVERY}. */
    public static int clampEvery(int every) {
        return Math.max(MIN_EVERY, Math.min(MAX_EVERY, every));
    }

    /** {@code sealChance} brought inside {@link #SEAL_NEVER}..{@link #SEAL_ALWAYS}. */
    public static int clampSeal(int chance) {
        return Math.max(SEAL_NEVER, Math.min(SEAL_ALWAYS, chance));
    }

    /** True when this lays any copies at all. */
    public boolean lays() {
        return kind.lays();
    }

    /**
     * True when the sealed-exit control means anything here — {@link Kind#RANDOM} alone.
     *
     * <p>Sealing the way straight back out is only fair when there is something unpredictable to go
     * and find. Under {@link Kind#ON} the copies are a lattice, so a walled exit would send a player
     * on a walk whose length they could work out in advance; under {@link Kind#OFF} it would wall the
     * only way onward there is.</p>
     */
    public boolean sealsApply() {
        return kind == Kind.RANDOM;
    }

    /** How often this room walls off the base pair's exit, 0..10, or 0 where the control is moot. */
    public int effectiveSealChance() {
        return sealsApply() ? sealChance : SEAL_NEVER;
    }

    /**
     * Read one stored segment. Total: an unreadable kind falls back to {@link Kind#ON} and an
     * unreadable or out-of-range spacing to {@link #DEFAULT_EVERY}, so a hand-edited typo stamps a
     * room rather than failing a pair.
     */
    public static PortalRoomExits parse(String segment) {
        if (segment == null) return ON;
        String text = segment.trim();
        if (text.isEmpty()) return ON;

        // kind[:every[:seal]] — every part after the first is optional, so every tag written before
        // that part existed still reads, to the default for the ones it does not name.
        String[] parts = text.split(SPACING_SEPARATOR, -1);
        return new PortalRoomExits(
            Kind.parse(parts[0]),
            parts.length > 1 ? parseInt(parts[1], DEFAULT_EVERY) : DEFAULT_EVERY,
            parts.length > 2 ? parseInt(parts[2], SEAL_NEVER) : SEAL_NEVER);
    }

    /** A number from the tag, or {@code fallback} when it is missing or not a number. */
    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * The segment to store.
     *
     * <p>Each trailing part is written only when it would change something, so a room that simply
     * wants copies round-trips as the bare {@code on} it reads most clearly as, and every tag written
     * before the sealed exit existed is re-written unchanged. {@link Kind#OFF} drops both — spacing
     * nothing, and sealing the way out of a room with no other way out, are not settings.</p>
     *
     * <p>The parts are positional, so a seal cannot be written without a spacing in front of it; at
     * its default the spacing goes in as the placeholder, which {@link #parse} reads back as the same
     * default.</p>
     */
    public String id() {
        if (!lays()) return kind.id();
        int seal = effectiveSealChance();
        if (seal != SEAL_NEVER) {
            return kind.id() + SPACING_SEPARATOR + every + SPACING_SEPARATOR + seal;
        }
        if (every == DEFAULT_EVERY) return kind.id();
        return kind.id() + SPACING_SEPARATOR + every;
    }

    /** Label for the editor row, e.g. {@code "On"}. The spacing has a stepper row of its own. */
    public String displayName() {
        return kind.displayName();
    }

    /** The kind after this one, wrapping, at the same spacing and seal. */
    public PortalRoomExits next() {
        return new PortalRoomExits(kind.next(), every, sealChance);
    }

    public PortalRoomExits withKind(Kind newKind) {
        return new PortalRoomExits(newKind, every, sealChance);
    }

    public PortalRoomExits withEvery(int newEvery) {
        return new PortalRoomExits(kind, newEvery, sealChance);
    }

    public PortalRoomExits withSealChance(int newChance) {
        return new PortalRoomExits(kind, every, newChance);
    }
}
