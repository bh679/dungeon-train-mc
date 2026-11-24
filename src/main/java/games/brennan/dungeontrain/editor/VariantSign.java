package games.brennan.dungeontrain.editor;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Per-entry options for a sign row ({@link SignVariants#isSign}): whether the sign picks its mount
 * when it spawns ({@link #auto}), and whether its text is the cell's shared text ({@link #shared}).
 *
 * <p>The four fixed mounts are not stored here: they <em>are</em> the row's block (a wall sign, a
 * standing sign, a ceiling hanging sign or a wall hanging sign of one wood), switched by
 * {@link SignVariants#withMount}. The text, colour, glow and wax live in the row's block-entity NBT
 * like any other sign. {@link #auto} rows are re-mounted by {@link VariantPostPass} once their
 * neighbours are in ({@link SignVariants#autoMount}).</p>
 *
 * <p>{@link #shared} is resolved when the text is edited, not at spawn: the editor writes the same
 * text into every shared sign row of the cell, so every placer keeps reading the row's own NBT.</p>
 *
 * <p>{@link #locked} goes further: every locked sign row of the cell holds the same mount, facing,
 * Auto, text and wax, so an edit to one is written into all of them ({@link SignRowEdits}). Only
 * the wood differs. A locked row is always {@link #shared} too.</p>
 *
 * <p>{@link #NONE} is the default and is omitted from JSON, so older templates round-trip
 * diff-clean. Sidecar token: a comma list of {@code auto}, {@code shared} and {@code locked}.</p>
 */
public record VariantSign(boolean auto, boolean shared, boolean locked) {

    /** Default: the captured mount, the row's own text. */
    public static final VariantSign NONE = new VariantSign(false, false, false);

    /** Canonical: a locked row shares its text. */
    public VariantSign {
        if (locked) shared = true;
    }

    /** Unlocked options. */
    public VariantSign(boolean auto, boolean shared) {
        this(auto, shared, false);
    }

    /** True when this is the no-op default — used to skip JSON / NBT emission. */
    public boolean isDefault() {
        return !auto && !shared && !locked;
    }

    /** Copy with {@link #auto} replaced. */
    public VariantSign withAuto(boolean on) {
        return new VariantSign(on, shared, locked);
    }

    /** Copy with {@link #shared} replaced; turning sharing off also unlocks. */
    public VariantSign withShared(boolean on) {
        return new VariantSign(auto, on, on && locked);
    }

    /** Copy with {@link #locked} replaced; unlocking leaves the text shared. */
    public VariantSign withLocked(boolean on) {
        return new VariantSign(auto, shared || on, on);
    }

    /** Sidecar / wire token: e.g. {@code "auto"}, {@code "auto,shared"}, {@code "locked"} or {@code ""}. */
    public String id() {
        StringBuilder out = new StringBuilder();
        if (auto) out.append("auto");
        // locked implies shared, so the token names only the stronger of the two.
        String share = locked ? "locked" : shared ? "shared" : null;
        if (share != null) out.append(out.length() > 0 ? "," : "").append(share);
        return out.toString();
    }

    /** Parse a token; {@code null} when malformed. {@code null} / blank parses to {@link #NONE}. */
    @Nullable
    public static VariantSign parse(@Nullable String token) {
        if (token == null || token.isBlank()) return NONE;
        boolean auto = false;
        boolean shared = false;
        boolean locked = false;
        for (String part : token.toLowerCase(Locale.ROOT).split(",")) {
            switch (part.trim()) {
                case "auto" -> auto = true;
                case "shared" -> shared = true;
                case "locked" -> locked = true;
                case "" -> { }
                default -> { return null; }
            }
        }
        return new VariantSign(auto, shared, locked);
    }
}
