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
 * <p>{@link #NONE} is the default and is omitted from JSON, so older templates round-trip
 * diff-clean. Sidecar token: {@code "auto"}, {@code "shared"} or {@code "auto,shared"}.</p>
 */
public record VariantSign(boolean auto, boolean shared) {

    /** Default: the captured mount, the row's own text. */
    public static final VariantSign NONE = new VariantSign(false, false);

    /** True when this is the no-op default — used to skip JSON / NBT emission. */
    public boolean isDefault() {
        return !auto && !shared;
    }

    /** Copy with {@link #auto} replaced. */
    public VariantSign withAuto(boolean on) {
        return new VariantSign(on, shared);
    }

    /** Copy with {@link #shared} replaced. */
    public VariantSign withShared(boolean on) {
        return new VariantSign(auto, on);
    }

    /** Sidecar / wire token: {@code "auto"}, {@code "shared"}, {@code "auto,shared"} or {@code ""}. */
    public String id() {
        if (auto && shared) return "auto,shared";
        if (auto) return "auto";
        if (shared) return "shared";
        return "";
    }

    /** Parse a token; {@code null} when malformed. {@code null} / blank parses to {@link #NONE}. */
    @Nullable
    public static VariantSign parse(@Nullable String token) {
        if (token == null || token.isBlank()) return NONE;
        boolean auto = false;
        boolean shared = false;
        for (String part : token.toLowerCase(Locale.ROOT).split(",")) {
            switch (part.trim()) {
                case "auto" -> auto = true;
                case "shared" -> shared = true;
                case "" -> { }
                default -> { return null; }
            }
        }
        return new VariantSign(auto, shared);
    }
}
