package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import io.github.mortuusars.exposure.client.image.Image;
import io.github.mortuusars.exposure.client.image.modifier.ImageEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.Optional;

/**
 * Cuts a worn paper's tears out of the picture printed on it. Exposure draws the photo on top of
 * its paper, so a tear in the paper alone would stop at the picture's edge; this makes the picture
 * transparent wherever the paper underneath has been torn away.
 */
final class TornEdgeEffect implements ImageEffect {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Exposure's paper texture is this many pixels square, with the picture inset on every side. */
    private static final int PAPER_SIZE = 64;
    private static final int PICTURE_INSET = 4;
    private static final int PICTURE_SIZE = PAPER_SIZE - 2 * PICTURE_INSET;

    private final String identifier;
    private final ResourceLocation paper;
    /** {@code torn[y * PAPER_SIZE + x]}: the paper has no pixel there. Loaded on first use. */
    private boolean[] torn;

    TornEdgeEffect(String identifier, ResourceLocation paper) {
        this.identifier = identifier;
        this.paper = paper;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public Image modify(Image image) {
        boolean[] mask = mask();
        int width = image.width();
        int height = image.height();
        return new Image() {
            @Override
            public int width() { return width; }

            @Override
            public int height() { return height; }

            @Override
            public int getPixelARGB(int x, int y) {
                int paperX = PICTURE_INSET + x * PICTURE_SIZE / width;
                int paperY = PICTURE_INSET + y * PICTURE_SIZE / height;
                return mask[paperY * PAPER_SIZE + paperX] ? 0 : image.getPixelARGB(x, y);
            }

            @Override
            public void close() { image.close(); }
        };
    }

    private synchronized boolean[] mask() {
        if (torn != null) return torn;
        boolean[] loaded = new boolean[PAPER_SIZE * PAPER_SIZE];
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(paper);
            if (resource.isPresent()) {
                try (InputStream in = resource.get().open(); NativeImage texture = NativeImage.read(in)) {
                    for (int y = 0; y < PAPER_SIZE && y < texture.getHeight(); y++) {
                        for (int x = 0; x < PAPER_SIZE && x < texture.getWidth(); x++) {
                            loaded[y * PAPER_SIZE + x] = (texture.getPixelRGBA(x, y) >>> 24) == 0;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // An unreadable paper just means an uncut picture.
            LOGGER.warn("[DungeonTrain] Could not read worn photo paper {}: {}", paper, e.toString());
        }
        torn = loaded;
        return torn;
    }
}
