package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small prints of kept photos: in an advancement's tooltip, and in the album screen's grid.
 *
 * <p>For the tooltip, the description gets {@link #SLOT_LINES} blank lines appended ({@link #withSlot}),
 * so both advancements screens size the box to fit. One of them is a marker line carrying the photo:
 * the screens' widget mixins watch each description line being drawn and, at the marker, draw the
 * print at that spot ({@link #drawIfSlot}). Prints are downsampled once per file and size, and cached.</p>
 */
public final class EarnedPhotoThumbnails {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Lines reserved under the description (9px each). */
    static final int SLOT_LINES = 6;
    /** Tooltip print edge in GUI pixels, leaving a gap line above it. */
    private static final int SIZE = (SLOT_LINES - 1) * 9;
    /** Pixels kept per edge for a tooltip print. */
    private static final int TOOLTIP_SAMPLE = 64;

    /** The marker line: draws nothing as text, carries the photo to draw in its place. */
    private record PhotoSlot(Path file) implements FormattedCharSequence {
        @Override
        public boolean accept(FormattedCharSink sink) {
            return true;
        }
    }

    private record Key(Path file, int sample) {}

    private static final Map<Key, ResourceLocation> TEXTURES = new HashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger();

    private EarnedPhotoThumbnails() {}

    /** {@code description} plus the reserved lines for {@code file}'s print; {@code description} itself when null. */
    public static List<FormattedCharSequence> withSlot(List<FormattedCharSequence> description, Path file) {
        if (file == null) return description;
        List<FormattedCharSequence> out = new ArrayList<>(description.size() + SLOT_LINES);
        out.addAll(description);
        out.add(FormattedCharSequence.EMPTY);
        out.add(new PhotoSlot(file));
        for (int i = 2; i < SLOT_LINES; i++) out.add(FormattedCharSequence.EMPTY);
        return out;
    }

    /** {@link #withSlot} for an advancement: its own photo once earned, else its album's latest. */
    public static List<FormattedCharSequence> withSlot(List<FormattedCharSequence> description,
                                                       ResourceLocation id, boolean earned) {
        return withSlot(description, EarnedPhotos.thumbnail(id, earned));
    }

    /** Called for every description line drawn: at the marker, draws its photo at (x, y). */
    public static void drawIfSlot(GuiGraphics g, FormattedCharSequence line, int x, int y) {
        if (line instanceof PhotoSlot slot) draw(g, slot.file(), x, y, SIZE, TOOLTIP_SAMPLE);
    }

    /** Draw {@code file}'s print {@code size} GUI pixels square at (x, y), sampled to {@code sample}px. */
    public static void draw(GuiGraphics g, Path file, int x, int y, int size, int sample) {
        ResourceLocation texture = texture(file, sample);
        if (texture == null) return;
        RenderSystem.enableBlend();
        g.blit(texture, x, y, size, size, 0, 0, sample, sample, sample, sample);
        RenderSystem.disableBlend();
    }

    /** Drop every cached print of {@code file} — a new photo was just kept there. */
    static void forget(Path file) {
        TEXTURES.entrySet().removeIf(e -> {
            if (!e.getKey().file().equals(file)) return false;
            if (e.getValue() != null) Minecraft.getInstance().getTextureManager().release(e.getValue());
            return true;
        });
    }

    private static ResourceLocation texture(Path file, int sample) {
        Key key = new Key(file, sample);
        if (TEXTURES.containsKey(key)) return TEXTURES.get(key);
        ResourceLocation location = null;
        try (InputStream in = Files.newInputStream(file); NativeImage full = NativeImage.read(in)) {
            NativeImage small = new NativeImage(sample, sample, false);
            int side = Math.min(full.getWidth(), full.getHeight());
            int ox = (full.getWidth() - side) / 2, oy = (full.getHeight() - side) / 2;
            for (int y = 0; y < sample; y++) {
                for (int x = 0; x < sample; x++) {
                    small.setPixelRGBA(x, y, full.getPixelRGBA(ox + x * side / sample, oy + y * side / sample));
                }
            }
            location = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID,
                    "earned_photo/thumb_" + NEXT.incrementAndGet());
            Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(small));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't load the photo print {}: {}", file, e.toString());
        }
        TEXTURES.put(key, location);
        return location;
    }
}
