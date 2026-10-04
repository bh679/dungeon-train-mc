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
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The small print of an earned photo drawn inside its advancement's tooltip.
 *
 * <p>The tooltip's description gets {@link #SLOT_LINES} blank lines appended ({@link #withSlot}), so both
 * advancements screens size the box to fit. The first of them is a marker line: the screens' widget
 * mixins watch each description line being drawn and, at the marker, draw the thumbnail at that spot
 * ({@link #drawIfSlot}). Thumbnails are downsampled once and cached per advancement.</p>
 */
public final class EarnedPhotoThumbnails {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Lines reserved under the description (9px each). */
    static final int SLOT_LINES = 6;
    /** Thumbnail edge in GUI pixels — the print on its own paper — leaving a gap line above it. */
    private static final int SIZE = (SLOT_LINES - 1) * 9;
    /** Pixels kept per thumbnail edge — plenty for a ~40px tooltip print. */
    private static final int SAMPLE = 64;

    /** A gap line, then the marker where the thumbnail is drawn. Unique instance, compared by identity. */
    private static final FormattedCharSequence SLOT = sink -> true;

    private static final Map<ResourceLocation, ResourceLocation> TEXTURES = new HashMap<>();

    private EarnedPhotoThumbnails() {}

    /** {@code description} plus the reserved thumbnail lines when {@code id} has a kept photo, else itself. */
    public static List<FormattedCharSequence> withSlot(List<FormattedCharSequence> description, ResourceLocation id) {
        if (!EarnedPhotos.has(id)) return description;
        List<FormattedCharSequence> out = new ArrayList<>(description.size() + SLOT_LINES);
        out.addAll(description);
        out.add(FormattedCharSequence.EMPTY);
        out.add(SLOT);
        for (int i = 2; i < SLOT_LINES; i++) out.add(FormattedCharSequence.EMPTY);
        return out;
    }

    /** Called for every description line drawn: at the marker, draws {@code id}'s thumbnail at (x, y). */
    public static void drawIfSlot(GuiGraphics g, FormattedCharSequence line, ResourceLocation id, int x, int y) {
        if (line != SLOT || id == null) return;
        ResourceLocation texture = texture(id);
        if (texture == null) return;
        RenderSystem.enableBlend();
        g.blit(texture, x, y, SIZE, SIZE, 0, 0, SAMPLE, SAMPLE, SAMPLE, SAMPLE);
        RenderSystem.disableBlend();
    }

    /** Drop {@code id}'s cached thumbnail — a new photo was just kept for it. */
    static void forget(ResourceLocation id) {
        ResourceLocation old = TEXTURES.remove(id);
        if (old != null) Minecraft.getInstance().getTextureManager().release(old);
    }

    private static ResourceLocation texture(ResourceLocation id) {
        if (TEXTURES.containsKey(id)) return TEXTURES.get(id);
        ResourceLocation location = null;
        try (InputStream in = Files.newInputStream(EarnedPhotos.file(id))) {
            NativeImage full = NativeImage.read(in);
            NativeImage small = new NativeImage(SAMPLE, SAMPLE, false);
            int side = Math.min(full.getWidth(), full.getHeight());
            int ox = (full.getWidth() - side) / 2, oy = (full.getHeight() - side) / 2;
            for (int y = 0; y < SAMPLE; y++) {
                for (int x = 0; x < SAMPLE; x++) {
                    small.setPixelRGBA(x, y, full.getPixelRGBA(ox + x * side / SAMPLE, oy + y * side / SAMPLE));
                }
            }
            full.close();
            location = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID,
                    "earned_photo/thumb/" + id.getNamespace() + "/" + id.getPath());
            Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(small));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't load the photo thumbnail for {}: {}", id, e.toString());
        }
        TEXTURES.put(id, location);
        return location;
    }
}
