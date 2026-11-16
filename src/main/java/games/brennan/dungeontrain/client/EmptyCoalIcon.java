package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.InputStream;

/**
 * The faded coal an empty Drifting Engine Block input shows: the game's own coal item, every pixel of
 * it one flat grey a shade darker than the slot (the slot is #8B8B8B).
 *
 * <p>Built at runtime from whatever coal texture the game has loaded rather than shipped as a copy, so
 * the outline is exactly coal's — a resource pack's coal included — and none of the art is ours to
 * redistribute.</p>
 */
final class EmptyCoalIcon {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation COAL = ResourceLocation.withDefaultNamespace("textures/item/coal.png");
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dynamic/empty_slot_coal");
    /** Opaque, ABGR as NativeImage stores it (a grey, so the channel order does not matter). */
    private static final int GREY = 0xFF7F7F7F;

    private static int width, height;

    private EmptyCoalIcon() {}

    /** Rebuild from the coal texture loaded now. Called as the screen opens, so a pack change is picked up. */
    static void refresh() {
        Minecraft mc = Minecraft.getInstance();
        try (InputStream in = mc.getResourceManager().open(COAL); NativeImage src = NativeImage.read(in)) {
            // A resource pack's coal may be an animated strip; take its first square frame.
            int w = src.getWidth(), h = Math.min(src.getHeight(), w);
            NativeImage out = new NativeImage(w, h, true);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out.setPixelRGBA(x, y, (src.getPixelRGBA(x, y) >>> 24) != 0 ? GREY : 0);
                }
            }
            mc.getTextureManager().register(ID, new DynamicTexture(out));
            width = w;
            height = h;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] could not build the empty engine-slot coal icon: {}", e.toString());
            width = height = 0;
        }
    }

    /** Draw it into a 16x16 slot at ({@code x}, {@code y}); nothing if it could not be built. */
    static void draw(GuiGraphics graphics, int x, int y) {
        if (width == 0) return;
        graphics.blit(ID, x, y, 16, 16, 0, 0, width, height, width, height);
    }
}
