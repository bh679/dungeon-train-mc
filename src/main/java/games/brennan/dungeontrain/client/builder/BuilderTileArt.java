package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The screenshot behind a builder mode, drawn wherever a mode needs to be shown as a picture.
 *
 * <p>Two places want it — the {@link TrainBuilderScreen} picker tile and the New screen's mode row —
 * and they must fail the same way: the tile art isn't in the repo yet, so a missing PNG has to
 * degrade to a flat slate panel rather than the black-and-magenta checkerboard, which reads as a
 * bug rather than as artwork that hasn't landed.</p>
 *
 * <p>Presence is probed per call site at construction time, not per frame, because screens rebuild
 * their widgets in {@code init()} — which is also what runs on a resource reload, so a texture that
 * appears later is picked up without a restart.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderTileArt {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int FALLBACK_BG = 0xFF3A4048;

    /** Pixel size of each mode's art, read once — needed to crop rather than stretch. */
    private static final Map<BuilderMode, Size> SIZES = new EnumMap<>(BuilderMode.class);

    /** A texture's real pixel dimensions. */
    record Size(int width, int height) {}

    private BuilderTileArt() {}

    static ResourceLocation textureFor(BuilderMode mode) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, mode.texturePath());
    }

    /** Whether this mode's PNG is present in the loaded resource packs. */
    static boolean isAvailable(BuilderMode mode) {
        return Minecraft.getInstance().getResourceManager().getResource(textureFor(mode)).isPresent();
    }

    /**
     * Fill {@code (x, y, w, h)} with the mode's art, or the slate fallback when {@code available}
     * is false. Callers pass their own probe result so the lookup isn't repeated every frame.
     */
    static void render(GuiGraphics g, BuilderMode mode, boolean available,
                       int x, int y, int w, int h, float alpha) {
        if (!available) {
            g.fill(x, y, x + w, y + h, FALLBACK_BG);
            return;
        }
        Size size = sizeOf(mode);
        if (size == null) {
            g.fill(x, y, x + w, y + h, FALLBACK_BG);
            return;
        }
        renderCover(g, textureFor(mode), size.width(), size.height(), x, y, w, h, alpha);
    }

    /**
     * Fill the rect with {@code texture}, scaled uniformly and centre-cropped.
     *
     * <p>The tile slots are wide and short while the art is whatever shape the screenshot was, so
     * mapping the whole image onto the whole rect squashes it — a carriage comes out visibly wrong.
     * Instead the widest centred sub-rect of the source that matches the destination's aspect ratio
     * is sampled, so the image keeps its proportions and the overflow is cropped off the long axis
     * rather than compressed into the short one.</p>
     */
    public static void renderCover(GuiGraphics g, ResourceLocation texture, int texW, int texH,
                            int x, int y, int w, int h, float alpha) {
        if (texW <= 0 || texH <= 0 || w <= 0 || h <= 0) {
            return;
        }
        double dstAspect = w / (double) h;
        double srcAspect = texW / (double) texH;

        int srcW = texW;
        int srcH = texH;
        if (srcAspect > dstAspect) {
            srcW = Math.max(1, (int) Math.round(texH * dstAspect));   // source too wide — trim the sides
        } else {
            srcH = Math.max(1, (int) Math.round(texW / dstAspect));   // source too tall — trim top and bottom
        }
        float u = (texW - srcW) / 2.0F;
        float v = (texH - srcH) / 2.0F;

        RenderSystem.enableBlend();
        g.setColor(1.0F, 1.0F, 1.0F, alpha);
        g.blit(texture, x, y, w, h, u, v, srcW, srcH, texW, texH);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * The mode art's pixel size, read from the resource pack once per mode.
     *
     * <p>Cropping needs real dimensions, and a {@code ResourceLocation} carries none — the blit's
     * "texture size" arguments are whatever the caller claims. Decoding the PNG header is the only
     * way to know, so it's done once and cached; a resource reload replaces the pack contents but
     * not the artwork's shape, so a stale entry is not a risk worth a listener for.</p>
     */
    private static Size sizeOf(BuilderMode mode) {
        Size cached = SIZES.get(mode);
        if (cached != null) {
            return cached;
        }
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager()
                .getResource(textureFor(mode));
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            Size size = new Size(image.getWidth(), image.getHeight());
            SIZES.put(mode, size);
            return size;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Builder tile art unreadable for {}: {}", mode.id(), e.toString());
            return null;
        }
    }
}
