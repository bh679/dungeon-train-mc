package games.brennan.dungeontrain.client.live.deathrec;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathRecEncoderArgsTest {

    @Test
    void inputQueueOnlyWhereFfmpegTakesIt() {
        List<String> queued = Arrays.asList(DeathRecEncoder.args(640, 360, 20, 4242, true));
        List<String> bare = Arrays.asList(DeathRecEncoder.args(640, 360, 20, 4242, false));
        assertEquals(queued, Arrays.asList(DeathRecEncoder.args(640, 360, 20, 4242)), "old overload, old arguments");
        assertEquals(2, queued.stream().filter("-thread_queue_size"::equals).count());
        assertFalse(bare.contains("-thread_queue_size"), "FFmpeg 9: none, or the recorder exits at once");
        assertTrue(bare.contains("tcp://127.0.0.1:4242"));
        assertTrue(bare.contains("-c:a"));
        assertEquals(queued.size() - 4, bare.size(), "only the two queue pairs differ");
        assertFalse(Arrays.asList(DeathRecEncoder.args(640, 360, 20, 0, true)).contains("-thread_queue_size"), "silent: one input, no queue");
    }
}
