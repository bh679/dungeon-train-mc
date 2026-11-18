package games.brennan.dungeontrain.echo;

/**
 * Names an echo's personality from its two PlayerMob disposition traits, for the death screen's
 * echo page. Pure (no Minecraft types) so the grid is unit-tested.
 *
 * <p>The classes follow how the echo actually behaves, using PlayerMob's
 * {@code DispositionResolver} thresholds: fight/flight {@code >= 5} stands and fights, below it
 * flees; friendliness {@code >= 5} greets, 3–4 only watches from a distance, 0–2 also has an inner
 * radius where it fights or flees a player who comes close.</p>
 *
 * <pre>
 *                     fights (ff >= 5)   flees (ff < 5)
 *   greets  (fr >= 5)  GUARDIAN           GENTLE_SOUL
 *   watches (fr 3–4)   SENTINEL           SKEPTIC
 *   hostile (fr 0–2)   BRUTE              RECLUSE
 * </pre>
 *
 * <p>Trait words reuse the relay's death-profile vocabulary ({@code web/js/death-profile.js}),
 * bucketed over the whole 0–10 range so each word lines up with the behaviour thresholds.</p>
 */
public final class EchoPersonality {

    /** PlayerMob {@code DispositionResolver.FF_FIGHT}: at or above, the echo fights. */
    static final int FIGHT_AT = 5;
    /** PlayerMob {@code DispositionResolver.FRIEND_GREET}: at or above, the echo greets. */
    static final int GREET_AT = 5;
    /** Friendliness at or above this (and below {@link #GREET_AT}) only watches; below, it is hostile up close. */
    static final int WATCH_AT = 3;

    public static final int MIN = 0;
    public static final int MAX = 10;

    /** The six personality classes, each with its lang-key suffix. */
    public enum Kind {
        GUARDIAN("guardian"),
        GENTLE_SOUL("gentle_soul"),
        SENTINEL("sentinel"),
        SKEPTIC("skeptic"),
        BRUTE("brute"),
        RECLUSE("recluse");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        /** Lang key of this class's display name. */
        public String langKey() {
            return "gui.dungeontrain.death.echo.class." + key;
        }
    }

    /** Which trait a word or bar describes. */
    public enum Trait {
        FIGHT_FLIGHT("fight", new String[] {"measured", "willing", "aggressive", "ferocious"}),
        FRIENDLINESS("friend", new String[] {"hostile", "wary", "amiable", "warm"}),
        REACTION("reaction", new String[] {"sluggish", "steady", "quick", "lightning"});

        private final String key;
        private final String[] words;

        Trait(String key, String[] words) {
            this.key = key;
            this.words = words;
        }

        /** Lang key of the trait's label ("fight / flight"). */
        public String labelKey() {
            return "gui.dungeontrain.death.echo.trait." + key;
        }
    }

    /**
     * PlayerMob's per-skill reaction speeds ({@code ReactionChannel.SPECIFIC}), in the order the
     * echo page draws them. Each carries its lang-key suffix; the page picks the item icon.
     */
    public enum Skill {
        MELEE("melee"),
        RANGED("ranged"),
        DEFENCE("defence"),
        BUILDING("building"),
        MOVEMENT("movement"),
        ITEMS("items");

        private final String key;

        Skill(String key) {
            this.key = key;
        }

        /** Lang key of this skill's name. */
        public String langKey() {
            return "gui.dungeontrain.death.echo.skill." + key;
        }
    }

    private EchoPersonality() {
    }

    /** The personality class for a pair of traits (each clamped to 0–10 first). */
    public static Kind classify(int fightFlight, int friendliness) {
        boolean fights = clamp(fightFlight) >= FIGHT_AT;
        int friend = clamp(friendliness);
        if (friend >= GREET_AT) {
            return fights ? Kind.GUARDIAN : Kind.GENTLE_SOUL;
        }
        if (friend >= WATCH_AT) {
            return fights ? Kind.SENTINEL : Kind.SKEPTIC;
        }
        return fights ? Kind.BRUTE : Kind.RECLUSE;
    }

    /**
     * Lang key of the word for {@code value} on {@code trait}: four buckets over 0–10 —
     * 0–2, 3–4, 5–7, 8–10 — so the middle split sits on the behaviour threshold at 5.
     */
    public static String wordKey(Trait trait, int value) {
        return "gui.dungeontrain.death.echo.word." + trait.words[bucket(value)];
    }

    /** The bucket index (0–3) {@link #wordKey} uses. */
    static int bucket(int value) {
        int v = clamp(value);
        if (v <= 2) return 0;
        if (v <= 4) return 1;
        if (v <= 7) return 2;
        return 3;
    }

    /** {@code value} clamped to the trait range. */
    public static int clamp(int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }
}
