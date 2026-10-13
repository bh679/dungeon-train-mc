package games.brennan.dungeontrain.client.live.audio;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveAudioPumpTest {

    @Test
    void soundIsPacedByThePictureFrameCount() {
        long lead = LiveAudioPump.delaySamples();
        assertEquals(LiveAudioPump.DELAY_MS * 48L, lead, "the delay is leading silence at 48 kHz");
        assertEquals(lead, LiveAudioPump.samplesDue(0, 20), "before any picture: just the delay");
        assertEquals(2400 + lead, LiveAudioPump.samplesDue(1, 20), "one frame at 20 fps = 50 ms = 2400 samples");
        assertEquals(48_000 + lead, LiveAudioPump.samplesDue(30, 30), "one second of picture = one second of sound");
        assertEquals(48_000L * 3600 + lead, LiveAudioPump.samplesDue(20L * 3600, 20), "an hour in, still exact");
    }

    @Test
    void onlyLoopbackPeersAreFed() throws Exception {
        assertTrue(LiveAudioPump.isLoopback(new InetSocketAddress(InetAddress.getLoopbackAddress(), 4000)));
        assertTrue(LiveAudioPump.isLoopback(new InetSocketAddress(InetAddress.getByName("::1"), 4000)));
        assertFalse(LiveAudioPump.isLoopback(new InetSocketAddress(InetAddress.getByName("192.168.1.20"), 4000)));
        assertFalse(LiveAudioPump.isLoopback(null));
    }
}
