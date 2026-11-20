package games.brennan.dungeontrain.echo;

/**
 * Describes an echo from its PlayerMob disposition traits, for the death screen's echo page. Pure
 * (no Minecraft types) so it is unit-tested.
 *
 * <p><b>Personality name</b> ({@link #personalityKeys}): PlayerMob's spawn-egg words on two axes —
 * friendliness (Very Skeptical / Skeptical / Friendly / Very Friendly) and fight/flight (Very Shy /
 * Shy / Aggressive / Very Aggressive), bucketed 0–2, 3–4, 5–7, 8–10 — shown as a pair; an echo in
 * the middle of both (4–6) is simply Passive.</p>
 *
 * <p><b>Trait words</b> ({@link #wordKey}): three per score, 0–10, for each trait; the page shows a
 * random one of the three.</p>
 *
 * <p><b>Behaviour</b> ({@link Kind}, for the portrait's performance) follows how the echo actually
 * behaves, using PlayerMob's
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

    /** Words offered per trait score; the page shows one at random. */
    public static final int WORDS_PER_SCORE = 3;

    /** How the echo behaves, which picks the portrait's performance. */
    public enum Kind { GUARDIAN, GENTLE_SOUL, SENTINEL, SKEPTIC, BRUTE, RECLUSE }

    /** Which trait a word or bar describes. */
    public enum Trait {
        FIGHT_FLIGHT("fight"),
        FRIENDLINESS("friend"),
        REACTION("reaction");

        private final String key;

        Trait(String key) {
            this.key = key;
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

    /**
     * The echo's hobbies that sit on the skill graph after the six {@link Skill}s: Reading (books
     * opened, time spent reading, and writing) and Trading (PlayerMob's "Shopping" score). Each
     * carries its lang-key suffix; the page picks the item icon.
     */
    public enum Hobby {
        READING("reading"),
        TRADING("trading");

        private final String key;

        Hobby(String key) {
            this.key = key;
        }

        /** Lang key of this hobby's name. */
        public String langKey() {
            return "gui.dungeontrain.death.echo.skill." + key;
        }
    }

    private EchoPersonality() {
    }

    /** How an echo with these traits behaves (each clamped to 0–10 first). */
    public static Kind behaviour(int fightFlight, int friendliness) {
        return classify(fightFlight, friendliness);
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
     * Lang key of word {@code variant} (0–2, wrapped) of the three for {@code value} on
     * {@code trait}: {@code gui.dungeontrain.death.echo.word.<trait>.<score>.<variant>}.
     */
    public static String wordKey(Trait trait, int value, int variant) {
        return "gui.dungeontrain.death.echo.word." + trait.key + "." + clamp(value) + "."
                + Math.floorMod(variant, WORDS_PER_SCORE);
    }

    /** Fight/flight's egg words by bucket: 0–2, 3–4, 5–7, 8–10. */
    private static final String[] FIGHT_MOODS = {"very_shy", "shy", "aggressive", "very_aggressive"};
    /** Friendliness's egg words by bucket: 0–2, 3–4, 5–7, 8–10. */
    private static final String[] FRIEND_MOODS = {"very_skeptical", "skeptical", "friendly", "very_friendly"};
    private static final String MOOD = "gui.dungeontrain.death.echo.mood.";
    /** Lang key joining the two personality words ("%s, %s"). */
    public static final String PAIR_KEY = "gui.dungeontrain.death.echo.mood_pair";

    /**
     * Lang keys of the echo's personality name: {friendliness word, fight word}, or just Passive
     * when both traits sit in the middle (4–6).
     */
    public static String[] personalityKeys(int fightFlight, int friendliness) {
        int ff = clamp(fightFlight);
        int fr = clamp(friendliness);
        if (ff >= 4 && ff <= 6 && fr >= 4 && fr <= 6) {
            return new String[] {MOOD + "passive"};
        }
        return new String[] {MOOD + FRIEND_MOODS[bucket(fr)], MOOD + FIGHT_MOODS[bucket(ff)]};
    }

    /** The bucket (0–3) of a score: 0–2, 3–4, 5–7, 8–10. */
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
