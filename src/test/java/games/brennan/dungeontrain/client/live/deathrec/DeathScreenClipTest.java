package games.brennan.dungeontrain.client.live.deathrec;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathScreenClipTest {

    @Test
    void cutsTheLastSecondsSilentAndSmallWithNoCrop() {
        List<String> a = List.of(DeathScreenClip.cutArgs(Path.of("/tmp/in.ts"), Path.of("/tmp/out.mp4")));
        int sseof = a.indexOf("-sseof");
        assertTrue(sseof >= 0 && sseof < a.indexOf("-i"), "seek from the end, before the input");
        assertEquals("-5.500", a.get(sseof + 1));
        assertTrue(a.contains("-an"), "silent, like the stream's clip");
        assertEquals("scale=640:-2", a.get(a.indexOf("-vf") + 1));
        assertFalse(a.stream().anyMatch(s -> s.contains("crop")), "no name pill to crop away");
        assertTrue(a.get(a.size() - 1).endsWith("out.mp4"));
    }
}
