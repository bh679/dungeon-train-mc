package games.brennan.dungeontrain.editor;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The size of the painting that hung in the template where a variant cell was captured — set on
 * the entry seeded from a hung painting ({@link PaintingPass#whole}), never by the painting popup.
 *
 * <p>It belongs to the template, not to the roll: whatever the cell's rows are changed to, every
 * spawn clears that painting's other blocks first ({@link PaintingPass#capturedFootprint}), so the
 * pick replaces the whole painting instead of hanging over it. Kept apart from the editable
 * {@link VariantPainting} for exactly that reason. {@link #NONE} is omitted from JSON.
 * Sidecar token: {@code "2x1"}.</p>
 */
public record HungPainting(int width, int height) {

    public static final HungPainting NONE = new HungPainting(0, 0);

    public HungPainting {
        if (width < 1 || height < 1 || width > VariantPainting.MAX_SIDE || height > VariantPainting.MAX_SIDE) {
            width = 0;
            height = 0;
        }
    }

    public boolean isDefault() {
        return width == 0;
    }

    /** Sidecar token, e.g. {@code "2x1"}. */
    public String id() {
        return width + "x" + height;
    }

    /** Parse a token; {@link #NONE} when blank or malformed. */
    public static HungPainting parse(@Nullable String token) {
        if (token == null) return NONE;
        String[] dims = token.trim().toLowerCase(Locale.ROOT).split("x");
        if (dims.length != 2) return NONE;
        try {
            return new HungPainting(Integer.parseInt(dims[0]), Integer.parseInt(dims[1]));
        } catch (NumberFormatException e) {
            return NONE;
        }
    }
}
