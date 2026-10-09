package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveEncoderArgsTest {

    @Test
    void oneGopPerSegmentAndSessionScopedOutputs() {
        List<String> a = Arrays.asList(LiveEncoder.args(1280, 720, 20, 2500, 10, Path.of("/tmp/live/abc")));
        int g = a.indexOf("-g");
        assertEquals("200", a.get(g + 1), "GOP = fps * segment seconds so every segment starts on a keyframe");
        assertEquals("200", a.get(a.indexOf("-keyint_min") + 1));
        assertEquals("10", a.get(a.indexOf("-hls_time") + 1));
        assertEquals("1280x720", a.get(a.indexOf("-s") + 1));
        assertTrue(a.contains("vflip"), "GL frames are bottom-up");
        assertTrue(a.get(a.size() - 1).endsWith("abc/live.m3u8"));
        assertTrue(a.get(a.indexOf("-hls_segment_filename") + 1).endsWith("abc/seg%05d.ts"));
        assertTrue(a.get(a.indexOf("-hls_flags") + 1).contains("temp_file"), "the playlist must only ever name finished segments");
    }
}
