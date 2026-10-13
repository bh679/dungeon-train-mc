package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveEncoderArgsTest {

    @Test
    void twoSecondKeyframesAndSessionScopedOutputs() {
        List<String> a = Arrays.asList(LiveEncoder.args(1280, 720, 20, 2500, 10, Path.of("/tmp/live/abc")));
        int g = a.indexOf("-g");
        assertEquals("40", a.get(g + 1), "GOP = fps * 2 s: Twitch needs a keyframe every <= 4 s on a copied stream");
        assertEquals("40", a.get(a.indexOf("-keyint_min") + 1));
        assertEquals("expr:gte(t,n_forced*2)", a.get(a.indexOf("-force_key_frames") + 1));
        assertEquals("10", a.get(a.indexOf("-hls_time") + 1), "segments stay 10 s (a multiple of the keyframe cadence)");
        assertEquals("1280x720", a.get(a.indexOf("-s") + 1));
        assertTrue(a.contains("vflip"), "GL frames are bottom-up");
        assertTrue(a.get(a.size() - 1).endsWith("abc/live.m3u8"));
        assertTrue(a.get(a.indexOf("-hls_segment_filename") + 1).endsWith("abc/seg%05d.ts"));
        assertTrue(a.get(a.indexOf("-hls_flags") + 1).contains("temp_file"), "the playlist must only ever name finished segments");
        int t = a.indexOf("-threads");
        assertEquals("2", a.get(t + 1), "libx264's default thread army competes with the game for cores");
        assertTrue(t > a.indexOf("-i"), "-threads must be an output (encoder) option, not an input one");
        assertFalse(a.contains("-c:a"), "no port, no sound: the stream stays video-only");
        assertFalse(a.contains("tcp://127.0.0.1:0"));
    }

    @Test
    void soundIsASecondInputAndThePictureIsUnchanged() {
        Path dir = Path.of("/tmp/live/abc");
        List<String> video = Arrays.asList(LiveEncoder.args(1280, 720, 20, 2500, 10, dir));
        List<String> a = Arrays.asList(LiveEncoder.args(1280, 720, 20, 2500, 10, dir, 51234));
        int sound = a.indexOf("tcp://127.0.0.1:51234");
        assertTrue(sound > 0, "ffmpeg reads the sound from the loopback port");
        assertEquals(List.of("-f", "s16le", "-ar", "48000", "-ac", "2", "-i"), a.subList(sound - 7, sound));
        assertFalse(a.contains("-itsoffset"), "the mpegts muxer swallows it; LiveAudioPump delays the sound itself");
        assertTrue(a.indexOf("-i") < sound, "the picture stays input 0 (stdin)");
        assertEquals("0:v:0", a.get(a.indexOf("-map") + 1));
        assertEquals("1:a:0", a.get(a.lastIndexOf("-map") + 1));
        assertEquals("aac", a.get(a.indexOf("-c:a") + 1));
        assertEquals("96k", a.get(a.indexOf("-b:a") + 1));
        assertTrue(a.indexOf("-c:a") < a.indexOf("hls"), "audio codec options belong to the HLS output");
        // Twitch copies the video: everything from -vf to the playlist is what it was without sound,
        // apart from the one audio-codec block.
        List<String> tail = new java.util.ArrayList<>(a.subList(a.indexOf("-vf"), a.size()));
        int codec = tail.indexOf("-c:a");
        assertEquals(List.of("-c:a", "aac", "-b:a", "96k", "-ar", "48000", "-ac", "2"), tail.subList(codec, codec + 8));
        tail.subList(codec, codec + 8).clear();
        assertEquals(video.subList(video.indexOf("-vf"), video.size()), tail);
    }
}
