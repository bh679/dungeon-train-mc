package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which segments hold the moment of death, and how the clip is cut and looped. */
class LiveDeathClipTest {

    @Test
    void windowWithinOneSegment() {
        LiveDeathClip.Window w = LiveDeathClip.window(27.0, 10);
        assertEquals(2, w.firstSegment());
        assertEquals(2, w.lastSegment());
        assertEquals(2.0, w.offsetS(), 1e-9);
        assertEquals(5.5, w.durationS(), 1e-9);
    }

    @Test
    void windowAcrossSegments() {
        LiveDeathClip.Window w = LiveDeathClip.window(21.0, 10);
        assertEquals(1, w.firstSegment(), "16 s is in segment 1");
        assertEquals(2, w.lastSegment(), "21.5 s is in segment 2");
        assertEquals(6.0, w.offsetS(), 1e-9);
    }

    @Test
    void windowWithShortSegments() {
        LiveDeathClip.Window w = LiveDeathClip.window(20.1, 2);
        assertEquals(7, w.firstSegment());
        assertEquals(10, w.lastSegment());
        assertEquals(1.1, w.offsetS(), 1e-9);
    }

    @Test
    void aDeathInTheFirstSecondsStartsAtZero() {
        LiveDeathClip.Window w = LiveDeathClip.window(2.0, 10);
        assertEquals(0, w.firstSegment());
        assertEquals(0.0, w.offsetS(), 1e-9);
        assertEquals(2.5, w.durationS(), 1e-9);
    }

    @Test
    void segmentNamesMatchTheEncoder() {
        assertEquals("seg00000.ts", LiveDeathClip.segmentName(0));
        assertEquals("seg00123.ts", LiveDeathClip.segmentName(123));
    }

    @Test
    void cutIsSilentSmallAndTrimmed() {
        String args = String.join(" ", LiveDeathClip.cutArgs(
            List.of(Path.of("/w/seg00001.ts"), Path.of("/w/seg00002.ts")), LiveDeathClip.window(21.0, 10), Path.of("/c/out.mp4")));
        assertTrue(args.contains("-i concat:/w/seg00001.ts|/w/seg00002.ts"), args);
        assertTrue(args.contains("-ss 6.000 -t 5.500"), args);
        assertTrue(args.contains("-an"), args);
        assertTrue(args.contains("scale=640:-2"), args);
        assertTrue(args.endsWith("/c/out.mp4"), args);
    }

    @Test
    void theLoopIsLocalAndInColour() {
        String args = String.join(" ", LiveHlsSession.localArgs("/c/death.mp4", 640, 360));
        assertTrue(args.contains("-protocol_whitelist file "), args);
        assertTrue(args.contains("-stream_loop -1 -i /c/death.mp4"), args);
        assertFalse(args.contains("gray"), args);
        assertFalse(args.contains("noise"), args);
        assertFalse(args.contains("http"), args);
    }
}
