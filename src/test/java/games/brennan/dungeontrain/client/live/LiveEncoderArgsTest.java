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
    void theWindowHoldsAMinuteWhateverTheSegmentLength() {
        List<String> ten = Arrays.asList(LiveEncoder.args(1280, 720, 20, 2500, 10, Path.of("/tmp/live/abc")));
        assertEquals("6", ten.get(ten.indexOf("-hls_list_size") + 1));
        Path dir = Path.of("/tmp/live/abc");
        List<String> quick = Arrays.asList(LiveEncoder.args(1280, 720, 20, 2500, LiveEncoder.Cadence.of(2), dir, 0, false));
        assertEquals("2", quick.get(quick.indexOf("-hls_time") + 1), "Quicker Streams: 2 s segments");
        assertEquals("30", quick.get(quick.indexOf("-hls_list_size") + 1), "30 x 2 s keeps the ~60 s window the Twitch pushers read");
        assertEquals("40", quick.get(quick.indexOf("-g") + 1), "still a keyframe every 2 s: one per segment");
        assertEquals(6, LiveEncoder.listSizeFor(10));
        assertEquals(30, LiveEncoder.listSizeFor(2));
        assertEquals(20, LiveEncoder.listSizeFor(3));
        assertEquals(6, LiveEncoder.listSizeFor(30), "never below 6");
        assertTrue(ten.get(ten.indexOf("-hls_flags") + 1).contains("program_date_time"),
            "each segment carries its wall-clock start, so players can tell how far behind they are");
    }

    @Test
    void keyframesFollowTheClaimedCadence() {
        Path dir = Path.of("/tmp/live/abc");
        List<String> three = Arrays.asList(LiveEncoder.args(1280, 720, 20, 2500, LiveEncoder.Cadence.of(3, 20, 3), dir, 0, false));
        assertEquals("3", three.get(three.indexOf("-hls_time") + 1));
        assertEquals("20", three.get(three.indexOf("-hls_list_size") + 1));
        assertEquals("60", three.get(three.indexOf("-g") + 1), "3 s keyframes at 20 fps: one per 3 s segment");
        assertEquals("60", three.get(three.indexOf("-keyint_min") + 1));
        assertEquals("expr:gte(t,n_forced*3)", three.get(three.indexOf("-force_key_frames") + 1));
        // Unset: 3 s segments cannot open on a 2 s grid, so they get 3 s keyframes by themselves.
        assertEquals(new LiveEncoder.Cadence(3, 20, 3), LiveEncoder.Cadence.of(3));
        assertEquals(new LiveEncoder.Cadence(10, 6, 2), LiveEncoder.Cadence.of(10));
        assertEquals(new LiveEncoder.Cadence(10, 6, 1), LiveEncoder.Cadence.of(10, 0, 1), "the relay may ask for 1 s");
        assertEquals(2, LiveEncoder.keyframeFor(10, 3), "3 does not divide 10: the default");
        assertEquals(2, LiveEncoder.keyframeFor(10, 5), "over Twitch's 4 s: the default");
        assertEquals(4, LiveEncoder.keyframeFor(4, 4));
        assertEquals(1, LiveEncoder.keyframeFor(5, 0));
        assertEquals(1, LiveEncoder.keyframeFor(3, 1));
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

    @Test
    void saveBandwidthCompressesHarderButKeepsKeyframesAndSegments() {
        Path dir = Path.of("/tmp/live/abc");
        List<String> off = Arrays.asList(LiveEncoder.args(1280, 720, 20, 2500, LiveEncoder.Cadence.of(10), dir, 0, false));
        List<String> a = Arrays.asList(LiveEncoder.args(1280, 720, 20, 2500, LiveEncoder.Cadence.of(10), dir, 0, true));
        assertEquals(Arrays.asList(LiveEncoder.args(1280, 720, 20, 2500, 10, dir)), off, "off is today's encoder");
        assertEquals("medium", a.get(a.indexOf("-preset") + 1));
        assertFalse(a.contains("-tune"), "no zerolatency: B-frames and lookahead are most of the saving");
        assertEquals("4", a.get(a.indexOf("-threads") + 1));
        assertEquals("1375k", a.get(a.indexOf("-b:v") + 1), "55% of the configured bitrate");
        assertEquals("1375k", a.get(a.indexOf("-maxrate") + 1));
        assertEquals("2750k", a.get(a.indexOf("-bufsize") + 1));
        for (String opt : List.of("-g", "-keyint_min", "-sc_threshold", "-force_key_frames", "-hls_time", "-hls_flags",
                "-hls_segment_filename", "-pix_fmt", "-c:v")) {
            assertEquals(off.get(off.lastIndexOf(opt) + 1), a.get(a.lastIndexOf(opt) + 1),
                    opt + " unchanged: Twitch copies the video and viewers join on segment boundaries");
        }
        assertEquals(off.get(off.size() - 1), a.get(a.size() - 1));
    }

    @Test
    void saveBandwidthBitrateHasAFloor() {
        assertEquals(2500, LiveEncoder.effectiveBitrateKbps(2500, false));
        assertEquals(1375, LiveEncoder.effectiveBitrateKbps(2500, true));
        assertEquals(500, LiveEncoder.effectiveBitrateKbps(600, true));
        assertEquals(4400, LiveEncoder.effectiveBitrateKbps(8000, true));
    }
}
