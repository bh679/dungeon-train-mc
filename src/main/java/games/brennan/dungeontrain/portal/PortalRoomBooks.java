package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.config.DungeonTrainConfig;

import java.util.Locale;

/**
 * Whether a portal room's bookshelves are stocked from one community author, whose, and which
 * authors are eligible.
 *
 * <p>Community books normally reach a player through a deliberately <i>mixed</i> pool — the selector
 * spreads picks across languages, curation tiers and unread books so no one writer dominates the
 * train. That is right for loot and wrong for a library. A room set to {@link Kind#MIX} picks one
 * author and deals their whole catalogue across its chiseled bookshelves, leaving every shelf past
 * the end of it empty — which is what a dozen books in a great hall is supposed to say.</p>
 *
 * <h2>Two values, three shares</h2>
 * <p>There is no "always the current player" setting. A room is either off or a weighted mix of the
 * three ways to name an author, and a room that wants exactly one of them says so with the weights —
 * {@code 1:0:0} is "always the reader's own writing". One control with three dials beats four
 * controls that are secretly the same control.</p>
 *
 * <p><b>Player and Signature are different questions.</b> A signature is free text typed at sign
 * time, so one account can write under several and several accounts can share one. The player share
 * names an account — everything this person wrote, whatever they signed it; the signature share names
 * the name on the cover — everything signed <i>Faulthurst</i>, whoever held the quill.</p>
 *
 * <h2>Why min AND max</h2>
 * <p>{@link #minBooks} keeps a room from locking to somebody with two books, which would leave all
 * but two shelves bare. {@link #maxBooks} is the opposite request and just as real: a room built to
 * feel like a modest private collection wants somebody with a dozen books, not the most prolific
 * writer on the server, and without an upper bound it would always draw the latter.</p>
 *
 * <p>Stored as the fifth segment of the room's {@code mode} tag — see {@link PortalRoomSettings},
 * which owns the encoding.</p>
 *
 * @param kind            whether the room stocks its shelves from one author at all
 * @param selfWeight      the reader's own share of the roll
 * @param playerWeight    a random account's share
 * @param signatureWeight a random pen name's share
 * @param minBooks        an eligible author has written MORE than this many approved books
 * @param maxBooks        ...and no more than this, or {@link #NO_MAXIMUM} for no upper bound
 */
public record PortalRoomBooks(Kind kind, int selfWeight, int playerWeight, int signatureWeight,
                              int minBooks, int maxBooks) {

    /** Whether the room stocks its shelves from one author. */
    public enum Kind {

        /** No author. The default, and what every room did before this existed. */
        OFF("off", "Off"),

        /** One author, rolled per room against this setting's three weights. */
        MIX("mix", "Author Mix");

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
            if (LEGACY_MIX_TOKEN.equals(key)) return MIX;
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

    /** Which of the three ways to name an author a roll came up with. */
    public enum Share {

        /** The player the room was stamped for. */
        SELF("self", "Self"),

        /** One account with an eligible number of approved books. */
        PLAYER("player", "Player"),

        /** One signature with an eligible number of approved books, whoever wrote under it. */
        SIGNATURE("signature", "Signature");

        private final String id;
        private final String displayName;

        Share(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        /** Label for this share's row in the edit screen. */
        public String displayName() {
            return displayName;
        }

        /** True when the author is the holder rather than somebody drawn from the directory. */
        public boolean isSelf() {
            return this == SELF;
        }

        /** The directory {@code kind} this share asks the relay for. */
        public String directoryKind() {
            return id;
        }
    }

    /** Separates the kind from the numbers inside this setting's own segment. */
    private static final String PART_SEPARATOR = ":";

    /** The share each way gets when the room does not say otherwise: an even three ways. */
    public static final int DEFAULT_WEIGHT = 1;

    /** A weight of zero takes that share out of the roll entirely — a valid thing for a room to say. */
    public static final int MIN_WEIGHT = 0;

    /**
     * Loosest weight worth storing. Two digits, past the point of usefulness on purpose: {@code 99:1:1}
     * is already "essentially always the reader's own".
     */
    public static final int MAX_WEIGHT = 99;

    /** {@link #maxBooks} for a room that does not care how prolific its author is. */
    public static final int NO_MAXIMUM = 0;

    public static final int MIN_BOOK_BOUND = 0;
    public static final int MAX_BOOK_BOUND = 999;

    /** What a room with nothing set behaves as: no author, exactly as before. */
    public static final PortalRoomBooks DEFAULT = new PortalRoomBooks(Kind.OFF);

    public PortalRoomBooks {
        if (kind == null) kind = Kind.OFF;
        selfWeight = clampWeight(selfWeight);
        playerWeight = clampWeight(playerWeight);
        signatureWeight = clampWeight(signatureWeight);
        minBooks = clampBound(minBooks);
        maxBooks = clampBound(maxBooks);
    }

    /** A kind at the default weights and the configured book range. */
    public PortalRoomBooks(Kind kind) {
        this(kind, DEFAULT_WEIGHT, DEFAULT_WEIGHT, DEFAULT_WEIGHT, defaultMinBooks(), NO_MAXIMUM);
    }

    /**
     * The floor a room starts at: the server's {@code portalRoomAuthorMinBooks}.
     *
     * <p>Read at construction rather than baked in, so lowering it on a small server changes what a
     * newly authored room starts from — while a room that has already said a number keeps it.</p>
     */
    public static int defaultMinBooks() {
        return DungeonTrainConfig.getPortalRoomAuthorMinBooks();
    }

    /** {@code weight} brought inside {@link #MIN_WEIGHT}..{@link #MAX_WEIGHT}. */
    public static int clampWeight(int weight) {
        return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, weight));
    }

    /** A book-count bound brought inside {@link #MIN_BOOK_BOUND}..{@link #MAX_BOOK_BOUND}. */
    public static int clampBound(int books) {
        return Math.max(MIN_BOOK_BOUND, Math.min(MAX_BOOK_BOUND, books));
    }

    /** True when the room stocks its shelves from an author at all. */
    public boolean locks() {
        return kind != Kind.OFF;
    }

    /** True when the weights and the book range mean anything — everything but Off. */
    public boolean weightsApply() {
        return locks();
    }

    /** Human-readable label for the editor row. */
    public String displayName() {
        return kind.displayName();
    }

    /** True when this room accepts an author who has written {@code count} approved books. */
    public boolean accepts(int count) {
        if (count <= minBooks) return false;
        return maxBooks == NO_MAXIMUM || count <= maxBooks;
    }

    /**
     * Which way of naming an author this room came up with, given a per-room {@code seed}.
     *
     * <p><b>All-zero weights roll evenly</b> rather than resolving to nothing. An author who zeroes
     * all three has said something contradictory, and the useful reading of it is "no preference"; a
     * room that could not pick an author because of arithmetic would look like a bug from inside.</p>
     */
    public Share resolveShare(long seed) {
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
        if (roll < self) return Share.SELF;
        if (roll < self + player) return Share.PLAYER;
        return Share.SIGNATURE;
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
     * unreadable number to its default, so a hand-edited typo stamps an ordinary room rather than
     * failing a pair's stamp.
     *
     * <p>The three single-author tokens this setting briefly had ({@code self} / {@code player} /
     * {@code signature}) read as a Mix weighted entirely to that share. None ever shipped, but a tag
     * written during development should keep meaning what it said rather than silently unlocking.</p>
     */
    public static PortalRoomBooks parse(String segment) {
        if (segment == null) return DEFAULT;
        String text = segment.trim();
        if (text.isEmpty()) return DEFAULT;

        String[] parts = text.split(PART_SEPARATOR, -1);
        PortalRoomBooks legacy = legacyShareToken(parts[0]);
        if (legacy != null) return legacy;

        // kind[:self:player:signature[:min:max]] — every group after the first is optional, so a
        // shorter tag still reads, to the default for what it does not name.
        return new PortalRoomBooks(
            Kind.parse(parts[0]),
            parts.length > 1 ? parseInt(parts[1], DEFAULT_WEIGHT) : DEFAULT_WEIGHT,
            parts.length > 2 ? parseInt(parts[2], DEFAULT_WEIGHT) : DEFAULT_WEIGHT,
            parts.length > 3 ? parseInt(parts[3], DEFAULT_WEIGHT) : DEFAULT_WEIGHT,
            parts.length > 4 ? parseInt(parts[4], defaultMinBooks()) : defaultMinBooks(),
            parts.length > 5 ? parseInt(parts[5], NO_MAXIMUM) : NO_MAXIMUM);
    }

    /** What {@link Kind#MIX} was called while it was one value among five. */
    private static final String LEGACY_MIX_TOKEN = "random";

    /** A development-era token as the equivalent Mix, or null when it is not one. */
    private static PortalRoomBooks legacyShareToken(String token) {
        String key = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        // The weighted roll was briefly called `random`, back when the three shares were also
        // selectable on their own. Same setting, same weights behind it — only the name moved.
        if (LEGACY_MIX_TOKEN.equals(key)) return null;
        for (Share share : Share.values()) {
            if (!share.id().equals(key)) continue;
            return new PortalRoomBooks(Kind.MIX,
                share == Share.SELF ? 1 : 0,
                share == Share.PLAYER ? 1 : 0,
                share == Share.SIGNATURE ? 1 : 0,
                defaultMinBooks(), NO_MAXIMUM);
        }
        return null;
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
     * <p>The two number groups are written only when they say something the defaults do not, so a
     * room that simply wants an author round-trips as the bare {@code mix} it reads most clearly as.
     * They are positional, so a book range cannot be written without the weights in front of it; at
     * their defaults the weights go in as the placeholder, which {@link #parse} reads back as the
     * same defaults.</p>
     */
    public String id() {
        if (!locks()) return kind.id();
        String weights = kind.id() + PART_SEPARATOR + selfWeight
            + PART_SEPARATOR + playerWeight + PART_SEPARATOR + signatureWeight;
        if (!isDefaultRange()) {
            return weights + PART_SEPARATOR + minBooks + PART_SEPARATOR + maxBooks;
        }
        return isEvenlyWeighted() ? kind.id() : weights;
    }

    /** True when no share is favoured — the state a bare {@code mix} reads back as. */
    public boolean isEvenlyWeighted() {
        return selfWeight == DEFAULT_WEIGHT
            && playerWeight == DEFAULT_WEIGHT
            && signatureWeight == DEFAULT_WEIGHT;
    }

    /** True when the room accepts whatever the server's floor accepts, with no ceiling. */
    public boolean isDefaultRange() {
        return minBooks == defaultMinBooks() && maxBooks == NO_MAXIMUM;
    }

    /** The kind after this one, wrapping, keeping everything else — the editor's Books button. */
    public PortalRoomBooks next() {
        return withKind(kind.next());
    }

    public PortalRoomBooks withKind(Kind newKind) {
        return new PortalRoomBooks(newKind, selfWeight, playerWeight, signatureWeight, minBooks, maxBooks);
    }

    public PortalRoomBooks withMinBooks(int books) {
        return new PortalRoomBooks(kind, selfWeight, playerWeight, signatureWeight, books, maxBooks);
    }

    public PortalRoomBooks withMaxBooks(int books) {
        return new PortalRoomBooks(kind, selfWeight, playerWeight, signatureWeight, minBooks, books);
    }

    /** This room's weight for {@code share} — the three rows read through one accessor. */
    public int weightFor(Share share) {
        return switch (share) {
            case SELF -> selfWeight;
            case PLAYER -> playerWeight;
            case SIGNATURE -> signatureWeight;
        };
    }

    /** This room with {@code share}'s weight set — the write half of {@link #weightFor}. */
    public PortalRoomBooks withWeightFor(Share share, int weight) {
        return switch (share) {
            case SELF -> new PortalRoomBooks(kind, weight, playerWeight, signatureWeight, minBooks, maxBooks);
            case PLAYER -> new PortalRoomBooks(kind, selfWeight, weight, signatureWeight, minBooks, maxBooks);
            case SIGNATURE -> new PortalRoomBooks(kind, selfWeight, playerWeight, weight, minBooks, maxBooks);
        };
    }
}
