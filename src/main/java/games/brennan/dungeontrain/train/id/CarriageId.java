package games.brennan.dungeontrain.train.id;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A carriage's id, written like a railway wagon number: {@code QXRT 4821-K 7B2C 1BS P}.
 *
 * <pre>
 *   QXRT       world mark — four letters hashed from the train generation seed ({@link WorldMark})
 *   4821       carriage index along the train; R4821 for an index behind the start (negative)
 *   -K         check character over the whole id (ISO 7064 MOD 37,36)
 *   7B2C       build context: difficulty level, phase and bands when the group was placed
 *   1BS        content version: the mod's minor version, base 36
 *   P T5 F S12 flags, only when set: portal, transition flatbeds (slot bits, base 36), an admin
 *              forcing setting was on, shared build 12
 * </pre>
 *
 * <p>The <b>short form</b> is the first two tokens only. It names the carriage inside its own world,
 * where {@link CarriageIdLedger} still holds the build context; the <b>full form</b> carries that
 * context itself, so it rebuilds the same carriage in any world with the same seed.</p>
 *
 * <p>The check character covers every token, the mark included, so a mistyped or swapped
 * character is reported as a typo rather than resolving to some other carriage. MOD 37,36 detects
 * every single-character substitution and all but one pair in 630 of neighbouring swaps.</p>
 *
 * @param mark    four letters A–Z
 * @param pIdx    absolute carriage index along the train; negative means behind the start
 * @param context build-time inputs the seed can't give back; empty in the short form
 */
public record CarriageId(String mark, int pIdx, Optional<Full> context) {

    /** The base-36 alphabet every token is written in. */
    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final int CONTEXT_WIDTH = 4;
    private static final int VERSION_WIDTH = 3;
    private static final int MAX_LEVEL = 0xFF;
    private static final int MAX_PHASE = 0x1F;
    private static final int MAX_BANDS = 0x3F;
    private static final int MAX_VERSION = 36 * 36 * 36 - 1;

    private static final Pattern MARK = Pattern.compile("[A-Z]{4}");
    private static final Pattern NUMBER = Pattern.compile("(R?)(\\d{1,10})-([0-9A-Z])");
    private static final Pattern SHARED = Pattern.compile("S(\\d{1,9})");
    private static final Pattern TRANSITION = Pattern.compile("T([0-9A-Z]{1,5})");

    public CarriageId {
        Objects.requireNonNull(mark, "mark");
        Objects.requireNonNull(context, "context");
        if (!MARK.matcher(mark).matches()) throw new IllegalArgumentException("mark must be four letters A-Z: " + mark);
        if (pIdx == Integer.MIN_VALUE) throw new IllegalArgumentException("pIdx out of range");
    }

    /** A short-form id: mark and carriage number only. */
    public static CarriageId shortForm(String mark, int pIdx) {
        return new CarriageId(mark, pIdx, Optional.empty());
    }

    /** A full-form id. */
    public static CarriageId fullForm(String mark, int pIdx, Full context) {
        return new CarriageId(mark, pIdx, Optional.of(context));
    }

    /** This id without its build context. */
    public CarriageId toShort() {
        return context.isEmpty() ? this : shortForm(mark, pIdx);
    }

    /**
     * The build-time inputs of a carriage group that its seed and index can't give back.
     *
     * @param level        difficulty level the group was gated at, 0–255
     * @param phase        {@code TrainPhase} ordinal the group was gated at, 0–31
     * @param bandMask     {@code BandFlavor} bits ({@code 1 << ordinal}) the group sat in, 0–63
     * @param version      the mod's minor version when the group was built, 0–46655
     * @param portal       the portal lottery fired for this group
     * @param transitionMask slots of this group a transition flatbed claimed ({@code 1 << slot}),
     *                       0 for none
     * @param forced       an admin forcing setting (Full / Whole Group cadence) was on; the rebuild
     *                     doesn't replay it, so the flag only warns
     * @param sharedBuild  the relay build stamped into this group, when one was
     */
    public record Full(int level, int phase, int bandMask, int version,
                       boolean portal, int transitionMask, boolean forced, OptionalInt sharedBuild) {
        public Full {
            Objects.requireNonNull(sharedBuild, "sharedBuild");
            requireRange("level", level, MAX_LEVEL);
            requireRange("phase", phase, MAX_PHASE);
            requireRange("bandMask", bandMask, MAX_BANDS);
            requireRange("version", version, MAX_VERSION);
            if (transitionMask < 0) throw new IllegalArgumentException("transitionMask must not be negative");
            if (sharedBuild.isPresent() && sharedBuild.getAsInt() < 0) {
                throw new IllegalArgumentException("sharedBuild must not be negative");
            }
        }

        /** The packed level/phase/bands value the context token encodes. */
        int packed() {
            return (level << 11) | (phase << 6) | bandMask;
        }

        static Full unpack(int packed, int version, boolean portal, int transitionMask, boolean forced,
                           OptionalInt sharedBuild) {
            return new Full((packed >>> 11) & MAX_LEVEL, (packed >>> 6) & MAX_PHASE, packed & MAX_BANDS,
                version, portal, transitionMask, forced, sharedBuild);
        }
    }

    /** The id as players read it, e.g. {@code QXRT 4821-K 7B2C 1BS P}. */
    public String format() {
        List<String> tail = tailTokens();
        String number = numberToken();
        char check = checkChar(mark + number + String.join("", tail));
        StringBuilder out = new StringBuilder(mark).append(' ').append(number).append('-').append(check);
        for (String token : tail) out.append(' ').append(token);
        return out.toString();
    }

    @Override
    public String toString() {
        return format();
    }

    private String numberToken() {
        return pIdx < 0 ? "R" + (-pIdx) : Integer.toString(pIdx);
    }

    private List<String> tailTokens() {
        List<String> tokens = new ArrayList<>();
        context.ifPresent(c -> {
            tokens.add(base36(c.packed(), CONTEXT_WIDTH));
            tokens.add(base36(c.version(), VERSION_WIDTH));
            if (c.portal()) tokens.add("P");
            if (c.transitionMask() != 0) tokens.add("T" + Integer.toString(c.transitionMask(), 36).toUpperCase(Locale.ROOT));
            if (c.forced()) tokens.add("F");
            c.sharedBuild().ifPresent(b -> tokens.add("S" + b));
        });
        return tokens;
    }

    /**
     * Read an id back. Case and runs of spaces are forgiven; anything else that doesn't match the
     * format, or a check character that disagrees, is an {@link IllegalArgumentException} whose
     * message says what was wrong.
     */
    public static CarriageId parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Carriage id is empty.");
        String[] tokens = text.trim().toUpperCase(Locale.ROOT).split("\\s+");
        if (tokens.length < 2) throw new IllegalArgumentException("Carriage id needs a world mark and a carriage number, like QXRT 4821-K.");
        String mark = tokens[0];
        if (!MARK.matcher(mark).matches()) throw new IllegalArgumentException("World mark must be four letters: " + mark);
        Matcher number = NUMBER.matcher(tokens[1]);
        if (!number.matches()) throw new IllegalArgumentException("Carriage number must look like 4821-K or R4821-K: " + tokens[1]);
        long magnitude = Long.parseLong(number.group(2));
        if (magnitude > Integer.MAX_VALUE) throw new IllegalArgumentException("Carriage number is too large: " + tokens[1]);
        int pIdx = number.group(1).isEmpty() ? (int) magnitude : -(int) magnitude;
        char check = number.group(3).charAt(0);

        StringBuilder tail = new StringBuilder();
        for (int i = 2; i < tokens.length; i++) tail.append(tokens[i]);
        String numberText = number.group(1) + number.group(2);
        if (checkChar(mark + numberText + tail) != check) {
            throw new IllegalArgumentException("Check character doesn't match; the id looks mistyped.");
        }
        if (tokens.length == 2) return shortForm(mark, pIdx);
        if (tokens.length < 4) throw new IllegalArgumentException("A full id needs both the context and the version token.");
        return fullForm(mark, pIdx, parseFull(tokens));
    }

    private static Full parseFull(String[] tokens) {
        int packed = parseBase36(tokens[2], CONTEXT_WIDTH, "context");
        int version = parseBase36(tokens[3], VERSION_WIDTH, "version");
        boolean portal = false, forced = false;
        int transitionMask = 0;
        OptionalInt shared = OptionalInt.empty();
        for (int i = 4; i < tokens.length; i++) {
            String flag = tokens[i];
            Matcher s = SHARED.matcher(flag);
            switch (flag) {
                case "P" -> portal = true;
                case "F" -> forced = true;
                default -> {
                    Matcher t = TRANSITION.matcher(flag);
                    if (t.matches()) {
                        transitionMask = Integer.parseInt(t.group(1), 36);
                        if (transitionMask == 0) throw new IllegalArgumentException("A transition flag needs at least one slot.");
                    } else if (s.matches()) {
                        shared = OptionalInt.of(Integer.parseInt(s.group(1)));
                    } else {
                        throw new IllegalArgumentException("Unknown flag: " + flag);
                    }
                }
            }
        }
        if (((packed >>> 11) & ~MAX_LEVEL) != 0) throw new IllegalArgumentException("Context token is out of range: " + tokens[2]);
        return Full.unpack(packed, version, portal, transitionMask, forced, shared);
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

    static String base36(int value, int width) {
        String digits = Integer.toString(value, 36).toUpperCase(Locale.ROOT);
        if (digits.length() > width) throw new IllegalArgumentException(value + " does not fit in " + width + " base-36 characters");
        return "0".repeat(width - digits.length()) + digits;
    }

    private static int parseBase36(String token, int width, String what) {
        if (token.length() != width) throw new IllegalArgumentException("The " + what + " token must be " + width + " characters: " + token);
        for (int i = 0; i < token.length(); i++) {
            if (ALPHABET.indexOf(token.charAt(i)) < 0) throw new IllegalArgumentException("The " + what + " token has a bad character: " + token);
        }
        return Integer.parseInt(token, 36);
    }

    private static void requireRange(String name, int value, int max) {
        if (value < 0 || value > max) throw new IllegalArgumentException(name + " must be 0-" + max + ": " + value);
    }
}
