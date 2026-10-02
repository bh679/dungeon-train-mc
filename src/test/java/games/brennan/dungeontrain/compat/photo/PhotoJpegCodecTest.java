package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoJpegCodecTest {

    /** Sixteen opaque greys, then a transparent last entry like Exposure's palettes carry. */
    private static int[] palette() {
        int[] colours = new int[17];
        for (int i = 0; i < 16; i++) {
            int v = i * 17;
            colours[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        colours[16] = 0x00FFFFFF;
        return colours;
    }

    /** A soft diagonal gradient — JPEG-friendly, so the round trip should land near the original. */
    private static byte[] gradient(int size) {
        byte[] pixels = new byte[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = (byte) ((x + y) * 15 / (2 * size - 2));
            }
        }
        return pixels;
    }

    @Test
    @DisplayName("a 320px photo round-trips at its size, small, and close to the original")
    void roundTrip() throws IOException {
        int size = 320;
        byte[] original = gradient(size);
        byte[] jpeg = PhotoJpegCodec.encode(size, size, original, palette(), PhotoJpegCodec.QUALITY);
        assertTrue(jpeg.length < 32 * 1024, "relay cap is 32 KB, got " + jpeg.length);

        PhotoJpegCodec.Decoded decoded = PhotoJpegCodec.decode(jpeg, palette());
        assertEquals(size, decoded.width());
        assertEquals(size, decoded.height());
        assertEquals(original.length, decoded.pixels().length);
        for (int i = 0; i < original.length; i++) {
            int index = decoded.pixels()[i] & 0xFF;
            assertTrue(index < 16, "pixel " + i + " used the transparent entry");
            assertTrue(Math.abs(index - original[i]) <= 1, "pixel " + i + " drifted from " + original[i] + " to " + index);
        }
    }

    @Test
    @DisplayName("the nearest colour is never a transparent palette entry")
    void nearestSkipsTransparent() {
        assertEquals(15, PhotoJpegCodec.nearestIndex(0xFFFFFF, palette()));
        assertEquals(0, PhotoJpegCodec.nearestIndex(0x000000, palette()));
    }

    @Test
    @DisplayName("bad input is refused rather than guessed at")
    void refusesBadInput() {
        assertThrows(IOException.class, () -> PhotoJpegCodec.encode(4, 4, new byte[15], palette(), 0.4f));
        assertThrows(IOException.class, () -> PhotoJpegCodec.decode(new byte[] {1, 2, 3}, palette()));
        byte[] big = new byte[400 * 400];
        assertThrows(IOException.class,
                () -> PhotoJpegCodec.decode(PhotoJpegCodec.encode(400, 400, big, palette(), 0.4f), palette()));
    }
}
