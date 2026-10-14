package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Per-entry <b>painting</b> options for a Fast Paintings painting row
 * ({@code fastpaintings:painting} — see {@link PaintingPass#isPainting}). When
 * {@link #on} the entry hangs a whole painting centred on the cell, the way a
 * vanilla painting hangs from the block it was placed against, instead of the
 * single captured block.
 *
 * <ul>
 *   <li>{@link #width} × {@link #height} — the size, in blocks; {@code 0 × 0}
 *       is <b>Any</b>: the largest pictures that fit, as vanilla picks.</li>
 *   <li>{@link #image} — one fixed picture ({@code painting_variant} id), or
 *       {@code null} for <b>Random</b> among the placeable pictures of the size.</li>
 * </ul>
 *
 * <p>{@link #NONE} (off) is the default and is omitted from JSON, so older
 * templates — whose world-seeded painting cells place block by block — spawn
 * unchanged. Sidecar token: {@code "any random"} / {@code "2x1 random"} /
 * {@code "4x3 minecraft:skeleton"}.</p>
 */
public record VariantPainting(boolean on, int width, int height, @Nullable ResourceLocation image) {

    /** Largest side Fast Paintings can address (its offsets are 0..15). */
    public static final int MAX_SIDE = 16;

    /** Default: not a painting option — the entry places its captured block. */
    public static final VariantPainting NONE = new VariantPainting(false, 0, 0, null);

    /** Any size, random picture — what a freshly added painting row starts as. */
    public static final VariantPainting ANY = new VariantPainting(true, 0, 0, null);

    public VariantPainting {
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) {
            width = 0;
            height = 0;
        }
    }

    /** A painting option of {@code width × height} ({@code 0, 0} = Any) and {@code image} ({@code null} = Random). */
    public static VariantPainting of(int width, int height, @Nullable ResourceLocation image) {
        return new VariantPainting(true, width, height, image);
    }

    /** True when this is the no-op default — used to skip JSON / NBT emission. */
    public boolean isDefault() {
        return !on;
    }

    /** True when no size is chosen. */
    public boolean anySize() {
        return width == 0;
    }

    /** True when the picture is rolled per spawn. */
    public boolean randomImage() {
        return image == null;
    }

    /** True when a picture of {@code w × h} is allowed by the size setting. */
    public boolean sizeMatches(int w, int h) {
        return anySize() || (w == width && h == height);
    }

    public VariantPainting withSize(int newWidth, int newHeight) {
        return new VariantPainting(on, newWidth, newHeight, image);
    }

    public VariantPainting withImage(@Nullable ResourceLocation newImage) {
        return new VariantPainting(on, width, height, newImage);
    }

    /** Sidecar / wire token, e.g. {@code "any random"} or {@code "2x1 minecraft:kebab"}. */
    public String id() {
        String size = anySize() ? "any" : width + "x" + height;
        return size + " " + (image == null ? "random" : image.toString());
    }

    /** Size label for the editor, e.g. {@code "2×1"} or {@code "Any"}. */
    public String sizeLabel() {
        return anySize() ? "Any" : width + "×" + height;
    }

    /** Parse a token; {@code null} when malformed. {@code null} / blank parses to {@link #NONE}. */
    @Nullable
    public static VariantPainting parse(@Nullable String token) {
        if (token == null || token.isBlank()) return NONE;
        String[] parts = token.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length != 2) return null;
        int w = 0;
        int h = 0;
        if (!parts[0].equals("any")) {
            String[] dims = parts[0].split("x");
            if (dims.length != 2) return null;
            try {
                w = Integer.parseInt(dims[0]);
                h = Integer.parseInt(dims[1]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE) return null;
        }
        ResourceLocation image = null;
        if (!parts[1].equals("random")) {
            image = ResourceLocation.tryParse(parts[1]);
            if (image == null) return null;
        }
        return of(w, h, image);
    }
}
