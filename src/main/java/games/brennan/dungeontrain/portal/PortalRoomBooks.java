package games.brennan.dungeontrain.portal;

import java.util.Locale;

/**
 * Whether the books found in a portal room are all by one author, and how that author is chosen.
 *
 * <p>Community books normally reach a player through a deliberately <i>mixed</i> pool — the selector
 * spreads picks across languages, curation tiers and unread books so no one writer dominates the
 * train. That is right for loot and wrong for a library: an author building a room full of
 * bookshelves had no way to say "these are all somebody's". Each {@link Kind} below is one answer to
 * whose.</p>
 *
 * <h2>Player and Signature are different questions</h2>
 * <p>A signature is free text typed at sign time, so one account can write under several and several
 * accounts can share one. {@link Kind#PLAYER} locks to an account — everything this person wrote,
 * whatever they signed it. {@link Kind#SIGNATURE} locks to the name on the cover — everything signed
 * <i>Faulthurst</i>, whoever held the quill. Neither is a special case of the other, so both are
 * offered.</p>
 *
 * <h2>What "more than ten books" is for</h2>
 * <p>{@link Kind#PLAYER} and {@link Kind#SIGNATURE} draw from authors above a threshold
 * ({@code portalRoomAuthorMinBooks}) because a room wants a catalogue, not a shelf: locking to
 * someone with two books would hand out those two over and over. {@link Kind#SELF} has no threshold —
 * finding your <i>own</i> two books in a room is the point of it — and rotates to a random author
 * for the many players who have written nothing at all.</p>
 *
 * <h2>Why the weights are per room rather than a config</h2>
 * <p>{@link Kind#RANDOM} rolls between the other three, so one room design can be your own library on
 * one run and a stranger's on the next. The three weights ride this setting rather than a server
 * config because they are an authoring decision, not a server tuning one: a cramped study might lean
 * heavily toward the reader's own writing while a great hall leans toward strangers, and both belong
 * in the same world.</p>
 *
 * <p><b>Off does not take part in the roll.</b> A Random room is always somebody's library. A room
 * that should sometimes be an ordinary mixed-pool room is set to {@link Kind#OFF}, which is a
 * different statement and reads as one in the editor.</p>
 *
 * <p>Stored as the fifth segment of the room's {@code mode} tag — see {@link PortalRoomSettings},
 * which owns the encoding. The four non-Random kinds write the bare token they always did, so every
 * tag written before Random existed round-trips unchanged.</p>
 *
 * @param kind            how the author is chosen
 * @param selfWeight      the reader's own share of a {@link Kind#RANDOM} roll
 * @param playerWeight    a random account's share
 * @param signatureWeight a random pen name's share
 */
public record PortalRoomBooks(Kind kind, int selfWeight, int playerWeight, int signatureWeight) {

    /** How a locked room picks the author whose books it serves. */
    public enum Kind {

        /** No lock. The default, and what every room did before this existed. */
        OFF("off", "Off"),

        /**
         * Books by whoever picks them up. Resolved per player, so two people in one room each read
         * their own writing — and a player who has written nothing gets a random author rather than
         * nothing.
         */
        SELF("self", "Current Player"),

        /** Books by one randomly chosen account with more than the configured number of approved books. */
        PLAYER("player", "Random Player"),

        /** Books carrying one randomly chosen signature, whoever the accounts behind it are. */
        SIGNATURE("signature", "Random Signature"),

        /** One of the three above, rolled per room against this setting's weights. */
        RANDOM("random", "Random Mix");

        private final String id;
        private final String displayName;

        Kind(String id, String displayName) {
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

        /** True when this kind locks the room's books to an author at all. */
        public boolean locks() {
            return this != OFF;
        }

        /**
         * True when the lock starts from the player holding the stack rather than from the directory
         * of prolific authors — the one kind that can resolve without anybody clearing the threshold.
         */
        public boolean startsFromSelf() {
            return this == SELF;
        }

        /**
         * The directory {@code kind} this value asks the relay for.
         *
         * <p>{@link #OFF} and {@link #RANDOM} never ask — callers gate on {@link #locks()} and resolve
         * Random to one of the other three first — but both name a real kind rather than returning
         * null into a URL builder.</p>
         */
        public String directoryKind() {
            return this == SIGNATURE ? "signature" : this == SELF ? "self" : "player";
        }

        /**
         * The kind named by {@code id}, or {@link #OFF} when it is null, blank or unrecognised.
         *
         * <p>Total, for the same reason {@link PortalRoomMode#parse} and {@link PortalRoomCopies#parse}
         * are: the tag is free text on disk, and a room whose setting was hand-edited to something
         * misspelt should stamp an ordinary room rather than fail the pair's stamp.</p>
         */
        public static Kind parse(String id) {
            if (id == null) return OFF;
            String key = id.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) return OFF;
            for (Kind k : values()) {
                if (k.id.equals(key)) return k;
            }
            return OFF;
        }

        /** The kind after this one, wrapping — what the editor's Books button steps through. */
        public Kind next() {
            Kind[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    /** Separates the kind from its weights inside this setting's own segment. */
    private static final String WEIGHT_SEPARATOR = ":";

    /** The share each kind gets in a Random roll when the room does not say otherwise: an even three ways. */
    public static final int DEFAULT_WEIGHT = 1;

    /** A weight of zero takes that kind out of the roll entirely — a valid thing for a room to say. */
    public static final int MIN_WEIGHT = 0;

    /**
     * Loosest weight worth storing.
     *
     * <p>Two digits, which is what keeps the whole settings tag inside
     * {@code EditorStatusPacket.MODE_TAG_MAX}. It is also past the point of usefulness: 99:1:1 is
     * already "essentially always the reader's own", and a room wanting more than that means
     * {@link Kind#SELF}.</p>
     */
    public static final int MAX_WEIGHT = 99;

    /** What a room with nothing set behaves as: the ordinary mixed pool, exactly as before. */
    public static final PortalRoomBooks DEFAULT = new PortalRoomBooks(Kind.OFF);

    public PortalRoomBooks {
        if (kind == null) kind = Kind.OFF;
        selfWeight = clampWeight(selfWeight);
        playerWeight = clampWeight(playerWeight);
        signatureWeight = clampWeight(signatureWeight);
    }

    /** A kind at the default even weights — every value but {@link Kind#RANDOM} ignores them. */
    public PortalRoomBooks(Kind kind) {
        this(kind, DEFAULT_WEIGHT, DEFAULT_WEIGHT, DEFAULT_WEIGHT);
    }

    /** {@code weight} brought inside {@link #MIN_WEIGHT}..{@link #MAX_WEIGHT}. */
    public static int clampWeight(int weight) {
        return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, weight));
    }

    /** True when the room locks its books to an author at all. */
    public boolean locks() {
        return kind.locks();
    }

    /** True when the weight steppers mean anything — only a Random room rolls. */
    public boolean weightsApply() {
        return kind == Kind.RANDOM;
    }

    /** Human-readable label for the editor row, e.g. {@code "Random Mix"}. */
    public String displayName() {
        return kind.displayName();
    }

    /**
     * The kind this room actually serves, given a per-room {@code seed}.
     *
     * <p>Everything but {@link Kind#RANDOM} answers itself. Random performs a weighted pick over the
     * three locking kinds — never Off, so a Random room is always somebody's library.</p>
     *
     * <p><b>All-zero weights roll uniformly</b> rather than resolving to nothing. An author who zeroes
     * all three has said something contradictory, and the useful reading of it is "no preference"; a
     * room that could not pick an author because of arithmetic would look like a bug from inside.</p>
     */
    public Kind resolveKind(long seed) {
        if (kind != Kind.RANDOM) return kind;
        int self = selfWeight;
        int player = playerWeight;
        int signature = signatureWeight;
        int total = self + player + signature;
        if (total <= 0) {
            self = 1;
            player = 1;
            signature = 1;
            total = 3;
        }
        int roll = Math.floorMod(mix(seed), total);
        if (roll < self) return Kind.SELF;
        if (roll < self + player) return Kind.PLAYER;
        return Kind.SIGNATURE;
    }

    /** Splittable-mix so consecutive pair keys do not roll in a visible pattern. */
    private static long mix(long seed) {
        long state = seed ^ 0x424F4F4B53L; // "BOOKS"
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        return state ^ (state >>> 31);
    }

    /**
     * Read one stored segment. Total: an unreadable kind falls back to {@link Kind#OFF} and an
     * unreadable weight to {@link #DEFAULT_WEIGHT}, so a hand-edited typo stamps an ordinary room
     * rather than failing a pair's stamp.
     */
    public static PortalRoomBooks parse(String segment) {
        if (segment == null) return DEFAULT;
        String text = segment.trim();
        if (text.isEmpty()) return DEFAULT;

        // kind[:self[:player[:signature]]] — every part after the first is optional, so every tag
        // written before the weights existed still reads, to the default for the ones it does not name.
        String[] parts = text.split(WEIGHT_SEPARATOR, -1);
        return new PortalRoomBooks(
            Kind.parse(parts[0]),
            parts.length > 1 ? parseInt(parts[1]) : DEFAULT_WEIGHT,
            parts.length > 2 ? parseInt(parts[2]) : DEFAULT_WEIGHT,
            parts.length > 3 ? parseInt(parts[3]) : DEFAULT_WEIGHT);
    }

    /** A weight from the tag, or {@link #DEFAULT_WEIGHT} when it is missing or not a number. */
    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_WEIGHT;
        }
    }

    /**
     * The segment to store.
     *
     * <p>The weights are written only by a room that rolls and only when they are not all even, so
     * the four kinds that existed before Random round-trip as the bare tokens they always were and no
     * stored tag needs migrating.</p>
     *
     * <p>The three are positional and go in together: a signature weight cannot be written without
     * the two in front of it, and writing one means writing all three rather than leaving a reader to
     * guess which was meant.</p>
     */
    public String id() {
        if (!weightsApply() || isEvenlyWeighted()) return kind.id();
        return kind.id() + WEIGHT_SEPARATOR + selfWeight
            + WEIGHT_SEPARATOR + playerWeight
            + WEIGHT_SEPARATOR + signatureWeight;
    }

    /** True when no kind is favoured — the state a bare {@code random} reads back as. */
    public boolean isEvenlyWeighted() {
        return selfWeight == DEFAULT_WEIGHT
            && playerWeight == DEFAULT_WEIGHT
            && signatureWeight == DEFAULT_WEIGHT;
    }

    /** The kind after this one, wrapping, keeping the weights — what the editor's Books button sends. */
    public PortalRoomBooks next() {
        return new PortalRoomBooks(kind.next(), selfWeight, playerWeight, signatureWeight);
    }

    public PortalRoomBooks withKind(Kind newKind) {
        return new PortalRoomBooks(newKind, selfWeight, playerWeight, signatureWeight);
    }

    public PortalRoomBooks withSelfWeight(int weight) {
        return new PortalRoomBooks(kind, weight, playerWeight, signatureWeight);
    }

    public PortalRoomBooks withPlayerWeight(int weight) {
        return new PortalRoomBooks(kind, selfWeight, weight, signatureWeight);
    }

    public PortalRoomBooks withSignatureWeight(int weight) {
        return new PortalRoomBooks(kind, selfWeight, playerWeight, weight);
    }

    /** This room's weight for {@code which} — the three steppers read through one accessor. */
    public int weightFor(Kind which) {
        return switch (which) {
            case SELF -> selfWeight;
            case PLAYER -> playerWeight;
            case SIGNATURE -> signatureWeight;
            default -> DEFAULT_WEIGHT;
        };
    }

    /** This room with {@code which}'s weight set — the write half of {@link #weightFor}. */
    public PortalRoomBooks withWeightFor(Kind which, int weight) {
        return switch (which) {
            case SELF -> withSelfWeight(weight);
            case PLAYER -> withPlayerWeight(weight);
            case SIGNATURE -> withSignatureWeight(weight);
            default -> this;
        };
    }
}
