package games.brennan.dungeontrain.compat.photo;

import com.mojang.logging.LogUtils;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.ExposureServer;
import io.github.mortuusars.exposure.data.ColorPalette;
import io.github.mortuusars.exposure.data.ColorPalettes;
import io.github.mortuusars.exposure.util.color.Color;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.level.storage.ExposureData;
import io.github.mortuusars.exposure.world.level.storage.ExposureIdentifier;
import io.github.mortuusars.exposure.world.level.storage.ExposureRepository;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * The photo the editor's Test the Carriage hangs in a random-photo frame when there is no community
 * photo to show (dev relay empty, discovery off, pool not fetched yet): a small drawn landscape, so
 * the author can still see the frame filled. Never placed on a real train.
 *
 * <p>Drawn once per world into Exposure's store under {@link #EXPOSURE_ID}, then reused.</p>
 */
public final class TestFramePhoto {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String EXPOSURE_ID = "dt_test_frame_photo";
    private static final int SIZE = 64;

    private static final int SKY_TOP = 0x3A7BD5;
    private static final int SKY_BOTTOM = 0xBFE3FF;
    private static final int SUN = 0xFFD54A;
    private static final int HILL_FAR = 0x5C9E4A;
    private static final int HILL_NEAR = 0x2F6B2A;

    private TestFramePhoto() {}

    /** A photograph of the test picture, or {@link ItemStack#EMPTY} if Exposure's store is unavailable. */
    public static ItemStack create(RegistryAccess registries) {
        try {
            ExposureRepository repository = ExposureServer.exposureRepository();
            if (repository.load(EXPOSURE_ID).getData().isEmpty()) {
                ColorPalette palette = ColorPalettes.get(registries, ColorPalettes.DEFAULT).value();
                repository.save(EXPOSURE_ID, new ExposureData(SIZE, SIZE, draw(palette), ColorPalettes.DEFAULT.location(),
                    ExposureData.Tag.EMPTY));
            }
            Frame frame = Frame.create().setIdentifier(ExposureIdentifier.id(EXPOSURE_ID)).toImmutable();
            ItemStack stack = new ItemStack(Exposure.Items.PHOTOGRAPH.get());
            stack.set(Exposure.DataComponents.PHOTOGRAPH_FRAME, frame);
            stack.set(Exposure.DataComponents.PHOTOGRAPH_TYPE, frame.type());
            return stack;
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Could not make the test frame photo: {}", e.toString());
            return ItemStack.EMPTY;
        }
    }

    private static byte[] draw(ColorPalette palette) {
        byte[] pixels = new byte[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                pixels[y * SIZE + x] = (byte) palette.closestTo(Color.rgb(colourAt(x, y)));
            }
        }
        return pixels;
    }

    /** Sky gradient, a sun, two rolling hills. */
    static int colourAt(int x, int y) {
        double farHill = SIZE * 0.62 + Math.sin(x * 0.11) * 5.0;
        double nearHill = SIZE * 0.78 + Math.sin(x * 0.07 + 2.0) * 6.0;
        if (y >= nearHill) return HILL_NEAR;
        if (y >= farHill) return HILL_FAR;
        int dx = x - SIZE * 3 / 4;
        int dy = y - SIZE / 4;
        if (dx * dx + dy * dy <= 49) return SUN;
        return lerp(SKY_TOP, SKY_BOTTOM, y / farHill);
    }

    private static int lerp(int from, int to, double t) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }
}
