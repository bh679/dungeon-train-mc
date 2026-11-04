package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveMicMeterTest {

    /** {@code n} samples of a sine at {@code amplitude} (0 to 1), as s16le bytes. */
    private static byte[] sine(int n, double amplitude) {
        byte[] pcm = new byte[2 * n];
        for (int i = 0; i < n; i++) {
            int s = (int) Math.round(amplitude * 32_767 * Math.sin(i * 0.1));
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    @Test
    void rmsOfSilenceIsZeroAndOfAFullScaleSineIsAboutSeventyPercent() {
        assertEquals(0f, LiveMicMeter.rms(new byte[4096], 0, 4096));
        assertEquals(0f, LiveMicMeter.rms(new byte[0], 0, 0), "no samples, no level");
        assertEquals(0.707f, LiveMicMeter.rms(sine(2048, 1.0), 0, 4096), 0.01f);
        assertEquals(0.354f, LiveMicMeter.rms(sine(2048, 0.5), 0, 4096), 0.01f, "linear in amplitude");
    }

    @Test
    void rmsReadsNegativeSamplesAsLoudness() {
        byte[] pcm = new byte[4];
        pcm[0] = (byte) 0x00; pcm[1] = (byte) 0x80; // -32768
        pcm[2] = (byte) 0xFF; pcm[3] = (byte) 0x7F; // 32767
        assertEquals(1f, LiveMicMeter.rms(pcm, 0, 4), 0.001f);
    }

    @Test
    void theMicIsTheMixLessTheGameInPower() {
        // Game at 0.2 and a voice at 0.15 add in power: the mix is 0.25; the mic reads 0.15 back out.
        float mix = (float) Math.sqrt(0.2 * 0.2 + 0.15 * 0.15);
        assertEquals(0.15f / LiveMicMeter.FULL_RMS, LiveMicMeter.target(mix, 0.2f, true), 0.001f);
        assertEquals(0f, LiveMicMeter.target(0.2f, 0.2f, true), "the game alone: no mic");
        assertEquals(0f, LiveMicMeter.target(0.1f, 0.2f, true), "a quieter mix than game never goes negative");
        assertEquals(1f, LiveMicMeter.target(1f, 0f, true), "clamped at full");
    }

    @Test
    void withoutAGameTrackTheMixStandsIn() {
        assertEquals(0.2f / LiveMicMeter.FULL_RMS, LiveMicMeter.target(0.2f, 0.9f, false), 0.001f,
            "a stale game reading is ignored");
        assertEquals(1f, LiveMicMeter.target(0.5f, 0f, false));
    }

    @Test
    void theLevelRisesFastAndSinksSlowly() {
        float up = LiveMicMeter.follow(0f, 1f);
        assertEquals(LiveMicMeter.ATTACK, up, 0.001f);
        float down = LiveMicMeter.follow(1f, 0f);
        assertEquals(1f - LiveMicMeter.RELEASE, down, 0.001f);
        assertTrue(up > 1f - down, "attack is quicker than release");
        float l = 1f;
        int chunks = 0;
        while (l > 0.05f) { l = LiveMicMeter.follow(l, 0f); chunks++; }
        assertTrue(chunks >= 10 && chunks <= 25, "about a second to sink from full at 85 ms a chunk: " + chunks);
        assertEquals(0.5f, LiveMicMeter.follow(0.5f, 0.5f), "at the target it stays");
    }

    @Test
    void theMeterReadsTheVoiceOutOfTheMix() {
        LiveMicMeter m = new LiveMicMeter();
        byte[] game = sine(2048, 0.3);
        m.game(game, 0, game.length);
        m.mix(game, 0, game.length);
        assertEquals(0f, m.level(), 0.01f, "game alone on both tracks: silent mic");
        byte[] louder = sine(2048, 0.6);
        for (int i = 0; i < 8; i++) m.mix(louder, 0, louder.length);
        assertTrue(m.level() > 0.9f, "a louder mix than game reads as the mic: " + m.level());
        m.mix(game, 0, game.length);
        assertTrue(m.level() > 0.5f && m.level() < 0.9f, "sinks back a step at a time: " + m.level());
    }
}
