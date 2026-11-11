package games.brennan.dungeontrain.train.id;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A carriage's id, written like a railway wagon number: {@code NOPI 12Y 0C9 L2K P-K}.
 *
 * <pre>
 *   NOPI       world mark: four letters hashed from the train generation seed ({@link WorldMark})
 *   12Y        carriage index along the train, base 36 (its magnitude; {@code B} marks a negative one)
 *   0          stage: the {@code TrainPhase} ordinal ({@code 0}–{@code T}), or for a styled band the
 *              band itself ({@code U}–{@code Z}, which names its phase too)
 *   C9         content version: the mod's minor version, base 36, wrapping every 1296 releases
 *   L2K ...    extras, only when set:
 *                L&lt;n&gt;  difficulty level, when it isn't the level the carriage number gives
 *                N&lt;n&gt;  band bits, when the stage character can't carry them
 *                B     the index is behind the start (negative)
 *                P     portal
 *                T&lt;n&gt;  transition flatbeds (slot bits)
 *                F     an admin forcing setting was on
 *                S&lt;n&gt;  shared build n
 *   -K         check character over everything before it (ISO 7064 MOD 37,36)
 * </pre>
 *
 * <p>Each token's length goes into the check along with its characters, so a space moved inside the
 * id ({@code L2 P} for {@code L2P}) is a typo too. MOD 37,36 detects every single-character
 * substitution and all but one pair in 630 of neighbouring swaps.</p>
 *
 * @param mark    four letters A–Z
 * @param pIdx    absolute carriage index along the train; negative means behind the start
 * @param context build-time inputs the seed can't give back
 */
public record CarriageId(String mark, int pIdx, Full context) {

    /** The base-36 alphabet every token is written in. */
    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** Stage characters at and above this one are bands; phases take the ones below. */
    static final int FIRST_BAND_CODE = 30;
    /**
     * The {@code TrainPhase} ordinal each {@code BandFlavor} ordinal sits in: BetterNether and BoP
     * Nether in the Nether, BetterEnd and BoP End in the End, WWOO and BoP in the overworld.
     */
    static final int[] BAND_PHASE = {1, 3, 1, 3, 0, 0};
    /** Versions wrap at this; the version only drives a "may have changed" warning. */
    static final int VERSION_MODULUS = 36 * 36;

    private static final int MAX_PHASE = FIRST_BAND_CODE - 1;
    private static final int MAX_BANDS = (1 << BAND_PHASE.length) - 1;

    private static final Pattern MARK = Pattern.compile("[A-Z]{4}");
    private static final Pattern NUMBER = Pattern.compile("[0-9A-Z]{1,7}");
    private static final Pattern STAGE = Pattern.compile("[0-9A-Z]{3}");
    private static final Pattern VALUE_EXTRA = Pattern.compile("([LNTS])([0-9A-Z]{1,7})");

    public CarriageId {
        Objects.requireNonNull(mark, "mark");
        Objects.requireNonNull(context, "context");
        if (!MARK.matcher(mark).matches()) throw new IllegalArgumentException("mark must be four letters A-Z: " + mark);
        if (pIdx == Integer.MIN_VALUE) throw new IllegalArgumentException("pIdx out of range");
    }

    /**
     * The build-time inputs of a carriage group that its seed and index can't give back.
     *
     * @param level          difficulty level the group was gated at, or empty when it was the level
     *                       its carriage number gives ({@link CarriageIds#baselineLevel})
     * @param phase          {@code TrainPhase} ordinal the group was gated at, 0–29
     * @param bandMask       {@code BandFlavor} bits ({@code 1 << ordinal}) the group sat in, 0–63
     * @param version        the mod's minor version when the group was built, modulo 1296
     * @param portal         the portal lottery fired for this group
     * @param transitionMask slots of this group a transition flatbed claimed ({@code 1 << slot}),
     *                       0 for none
     * @param forced         an admin forcing setting (Full / Whole Group cadence) was on; the rebuild
     *                       doesn't replay it, so the flag only warns
     * @param sharedBuild    the relay build stamped into this group, when one was
     */
    public record Full(OptionalInt level, int phase, int bandMask, int version,
                       boolean portal, int transitionMask, boolean forced, OptionalInt sharedBuild) {
        public Full {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(sharedBuild, "sharedBuild");
            if (level.isPresent()) requireRange("level", level.getAsInt(), Integer.MAX_VALUE);
            requireRange("phase", phase, MAX_PHASE);
            requireRange("bandMask", bandMask, MAX_BANDS);
            requireRange("version", version, VERSION_MODULUS - 1);
            if (transitionMask < 0) throw new IllegalArgumentException("transitionMask must not be negative");
            if (sharedBuild.isPresent() && sharedBuild.getAsInt() < 0) {
                throw new IllegalArgumentException("sharedBuild must not be negative");
            }
        }

        /** The band this context's stage character names, or -1 when it names a plain phase. */
        int bandCode() {
            if (Integer.bitCount(bandMask) != 1) return -1;
            int band = Integer.numberOfTrailingZeros(bandMask);
            return BAND_PHASE[band] == phase ? band : -1;
        }
    }

    /** The id as players read it, e.g. {@code NOPI 12Y 0C9 L2K-K}. */
    public String format() {
        List<String> tokens = tokens();
        return String.join(" ", tokens) + "-" + checkChar(checkPayload(tokens));
    }

    @Override
    public String toString() {
        return format();
    }

    private List<String> tokens() {
        Full c = context;
        int band = c.bandCode();
        List<String> tokens = new ArrayList<>();
        tokens.add(mark);
        tokens.add(base36(Math.abs((long) pIdx)));
        tokens.add(ALPHABET.charAt(band >= 0 ? FIRST_BAND_CODE + band : c.phase())
            + pad(base36(c.version()), 2));
        c.level().ifPresent(l -> tokens.add("L" + base36(l)));
        if (band < 0 && c.bandMask() != 0) tokens.add("N" + base36(c.bandMask()));
        if (pIdx < 0) tokens.add("B");
        if (c.portal()) tokens.add("P");
        if (c.transitionMask() != 0) tokens.add("T" + base36(c.transitionMask()));
        if (c.forced()) tokens.add("F");
        c.sharedBuild().ifPresent(b -> tokens.add("S" + base36(b)));
        return tokens;
    }

    /**
     * Read an id back. Case and runs of spaces are forgiven; anything else that doesn't match the
     * format, or a check character that disagrees, is an {@link IllegalArgumentException} whose
     * message says what was wrong.
     */
    public static CarriageId parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Carriage id is empty.");
        List<String> tokens = new ArrayList<>(List.of(text.trim().toUpperCase(Locale.ROOT).split("\\s+")));
        String last = tokens.get(tokens.size() - 1);
        int dash = last.lastIndexOf('-');
        if (dash <= 0 || dash != last.length() - 2) {
            throw new IllegalArgumentException("Carriage id must end with a check character, like NOPI K 0C9-J.");
        }
        char check = last.charAt(dash + 1);
        tokens.set(tokens.size() - 1, last.substring(0, dash));
        if (tokens.size() < 3) throw new IllegalArgumentException("Carriage id needs a world mark, carriage number and stage, like NOPI K 0C9-J.");
        for (String token : tokens) {
            for (int i = 0; i < token.length(); i++) {
                if (ALPHABET.indexOf(token.charAt(i)) < 0) throw new IllegalArgumentException("Carriage id has a character it can't contain: " + token);
            }
        }
        if (ALPHABET.indexOf(check) < 0 || checkChar(checkPayload(tokens)) != check) {
            throw new IllegalArgumentException("Check character doesn't match; the id looks mistyped.");
        }

        String mark = tokens.get(0);
        if (!MARK.matcher(mark).matches()) throw new IllegalArgumentException("World mark must be four letters: " + mark);
        if (!NUMBER.matcher(tokens.get(1)).matches()) throw new IllegalArgumentException("Carriage number is malformed: " + tokens.get(1));
        long magnitude = Long.parseLong(tokens.get(1), 36);
        if (magnitude > Integer.MAX_VALUE) throw new IllegalArgumentException("Carriage number is too large: " + tokens.get(1));
        String stage = tokens.get(2);
        if (!STAGE.matcher(stage).matches()) throw new IllegalArgumentException("The stage and version token must be three characters: " + stage);
        return parseRest(mark, (int) magnitude, stage, tokens.subList(3, tokens.size()));
    }

    private static CarriageId parseRest(String mark, int magnitude, String stage, List<String> extras) {
        int code = ALPHABET.indexOf(stage.charAt(0));
        int phase = code >= FIRST_BAND_CODE ? BAND_PHASE[code - FIRST_BAND_CODE] : code;
        int bandMask = code >= FIRST_BAND_CODE ? 1 << (code - FIRST_BAND_CODE) : 0;
        int version = Integer.parseInt(stage.substring(1), 36);
        OptionalInt level = OptionalInt.empty();
        OptionalInt shared = OptionalInt.empty();
        boolean behind = false, portal = false, forced = false;
        int transitionMask = 0;
        List<Character> seen = new ArrayList<>();
        for (String extra : extras) {
            char kind = extra.charAt(0);
            if (seen.contains(kind)) throw new IllegalArgumentException("Extra appears twice: " + extra);
            seen.add(kind);
            switch (extra) {
                case "B" -> behind = true;
                case "P" -> portal = true;
                case "F" -> forced = true;
                default -> {
                    Matcher m = VALUE_EXTRA.matcher(extra);
                    if (!m.matches()) throw new IllegalArgumentException("Unknown extra: " + extra);
                    long value = Long.parseLong(m.group(2), 36);
                    if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("Extra is too large: " + extra);
                    switch (kind) {
                        case 'L' -> level = OptionalInt.of((int) value);
                        case 'N' -> {
                            if (code >= FIRST_BAND_CODE) throw new IllegalArgumentException("A band stage can't also carry band bits: " + extra);
                            bandMask = (int) value;
                        }
                        case 'T' -> transitionMask = (int) value;
                        default -> shared = OptionalInt.of((int) value);
                    }
                }
            }
        }
        if (behind && magnitude == 0) throw new IllegalArgumentException("Carriage 0 can't be behind the start.");
        Full context = new Full(level, phase, bandMask, version, portal, transitionMask, forced, shared);
        return new CarriageId(mark, behind ? -magnitude : magnitude, context);
    }

    /** What the check character covers: each token's length, then its characters. */
    private static String checkPayload(List<String> tokens) {
        StringBuilder payload = new StringBuilder();
        for (String token : tokens) {
            payload.append(ALPHABET.charAt(Math.min(token.length(), ALPHABET.length() - 1))).append(token);
        }
        return payload.toString();
    }

    /**
     * ISO 7064 MOD 37,36 hybrid check character over {@code payload} (base-36 characters only).
     * Detects every single substitution and all but one pair in 630 of adjacent transpositions.
     */
    static char checkChar(String payload) {
        int p = 36;
        for (int i = 0; i < payload.length(); i++) {
            int v = ALPHABET.indexOf(payload.charAt(i));
            if (v < 0) throw new IllegalArgumentException("Not a base-36 character: " + payload.charAt(i));
            int s = (p + v) % 36;
            if (s == 0) s = 36;
            p = (s * 2) % 37;
        }
        return ALPHABET.charAt((37 - p) % 36);
    }

    static String base36(long value) {
        return Long.toString(value, 36).toUpperCase(Locale.ROOT);
    }

    private static String pad(String digits, int width) {
        return "0".repeat(Math.max(0, width - digits.length())) + digits;
    }

    private static void requireRange(String name, int value, int max) {
        if (value < 0 || value > max) throw new IllegalArgumentException(name + " must be 0-" + max + ": " + value);
    }
}
