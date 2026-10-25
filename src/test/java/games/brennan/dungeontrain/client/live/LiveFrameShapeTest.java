package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Each decoded frame names its own size, so the picture keeps the streamer's shape. */
class LiveFrameShapeTest {

    /** Exactly what ffmpeg's pam encoder writes for an RGBA frame. */
    static final String FFMPEG_640x480 = "P7\nWIDTH 640\nHEIGHT 480\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n";

    @Test
    void readsFfmpegsHeaderAndStopsAtThePixels() throws IOException {
        InputStream in = new ByteArrayInputStream((FFMPEG_640x480 + "PIXELS").getBytes(StandardCharsets.US_ASCII));
        assertEquals(new LiveFrameShape.Size(640, 480), LiveFrameShape.read(in));
        assertEquals('P', in.read(), "the pixels are left for the frame read");
    }

    @Test
    void cleanEndOfStreamIsNull() throws IOException {
        assertNull(LiveFrameShape.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void cutOffOrEndlessHeaderThrows() {
        assertThrows(IOException.class, () -> LiveFrameShape.read(stream("P7\nWIDTH 640\n")));
        assertThrows(IOException.class, () -> LiveFrameShape.read(stream("P7\n" + "x".repeat(400))));
    }

    @Test
    void anythingButSaneRgbaIsRefused() {
        assertNull(LiveFrameShape.parse(FFMPEG_640x480.replace("RGB_ALPHA", "RGB").replace("DEPTH 4", "DEPTH 3")));
        assertNull(LiveFrameShape.parse(FFMPEG_640x480.replace("MAXVAL 255", "MAXVAL 65535")));
        assertNull(LiveFrameShape.parse(FFMPEG_640x480.replace("WIDTH 640", "WIDTH 99999")));
        assertNull(LiveFrameShape.parse(FFMPEG_640x480.replace("HEIGHT 480", "HEIGHT x")));
        assertNull(LiveFrameShape.parse("P6\n640 480\n255\n"));
        assertNull(LiveFrameShape.parse(null));
    }

    @Test
    void ratioIsWidthOverHeight() {
        assertEquals(4f / 3f, new LiveFrameShape.Size(640, 480).ratio(), 1e-6);
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }
}
