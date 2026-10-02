package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoPngCodecTest {

    /** 255 opaque colours and a transparent last entry, like Exposure's palettes carry. */
    private static int[] palette() {
        int[] colours = new int[256];
        for (int i = 0; i < 255; i++) colours[i] = 0xFF000000 | (i << 16) | ((255 - i) << 8) | (i * 7 & 0xFF);
        return colours;
    }

    private static byte[] noise(int size, long seed) {
        byte[] pixels = new byte[size * size];
        new Random(seed).nextBytes(pixels);
        return pixels;
    }

    @Test
    @DisplayName("a 640px photo comes back pixel for pixel")
    void roundTrip() throws IOException {
        byte[] original = noise(640, 1);
        PhotoPngCodec.Decoded decoded = PhotoPngCodec.decode(PhotoPngCodec.encode(640, 640, original, palette()));
        assertEquals(640, decoded.width());
        assertEquals(640, decoded.height());
        assertArrayEquals(original, decoded.pixels());
    }

    @Test
    @DisplayName("the file is a real PNG any viewer can open, with the palette's colours")
    void readableByOtherDecoders() throws IOException {
        byte[] pixels = new byte[4 * 4];
        pixels[5] = 10;
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(PhotoPngCodec.encode(4, 4, pixels, palette())));
        assertEquals(4, image.getWidth());
        assertEquals(palette()[10], image.getRGB(1, 1));
        assertEquals(palette()[0], image.getRGB(0, 0));
    }

    @Test
    @DisplayName("a flat photo compresses far below the relay's 256 KB cap")
    void compresses() throws IOException {
        assertTrue(PhotoPngCodec.encode(640, 640, new byte[640 * 640], palette()).length < 8 * 1024);
    }

    @Test
    @DisplayName("bad input is refused rather than guessed at")
    void refusesBadInput() throws IOException {
        assertThrows(IOException.class, () -> PhotoPngCodec.encode(4, 4, new byte[15], palette()));
        assertThrows(IOException.class, () -> PhotoPngCodec.decode(new byte[] {1, 2, 3}));
        byte[] big = PhotoPngCodec.encode(641, 641, new byte[641 * 641], palette());
        assertThrows(IOException.class, () -> PhotoPngCodec.decode(big));
        byte[] cut = PhotoPngCodec.encode(8, 8, noise(8, 2), palette());
        assertThrows(IOException.class, () -> PhotoPngCodec.decode(Arrays.copyOf(cut, cut.length - 30)));
    }
}
