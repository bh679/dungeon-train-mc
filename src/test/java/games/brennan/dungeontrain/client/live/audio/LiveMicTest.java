package games.brennan.dungeontrain.client.live.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The relay's mid-stream word on the mic, and what the heartbeat reports as "on". No capture device is opened. */
class LiveMicTest {

    @AfterEach
    void endStream() {
        LiveMic.endStream();
    }

    @Test
    void aBanOnAHeartbeatSilencesTheVoiceAndSaysSoOnce() {
        LiveMic.startStream(true);
        assertFalse(LiveMic.barred());
        assertFalse(LiveMic.takeBarredNotice(), "nothing to say before a ban");

        assertTrue(LiveMic.relayAllows(false), "the first ban is a change");
        assertTrue(LiveMic.barred());
        assertFalse(LiveMic.on(), "the switch counts for nothing while barred");
        assertFalse(LiveMic.transmitting());
        assertFalse(LiveMic.heartbeatMicOn());
        assertEquals(0f, LiveMic.level(), "no level fill while barred");
        assertEquals(1f, LiveMic.mixGameGain(), "the game stays at full in the mix while barred");
        assertEquals(LiveMic.Toggle.BARRED, LiveMic.toggle(), "the switch does nothing while barred");

        assertTrue(LiveMic.takeBarredNotice(), "the chat line, once");
        assertFalse(LiveMic.takeBarredNotice(), "and not again");
        assertFalse(LiveMic.relayAllows(false), "the next heartbeat's ban is not news");
        assertFalse(LiveMic.takeBarredNotice());
    }

    @Test
    void anUnbanOnALaterHeartbeatLetsTheMicThroughAgain() {
        LiveMic.startStream(true);
        LiveMic.relayAllows(false);
        LiveMic.relayAllows(true);
        assertFalse(LiveMic.barred());
        assertTrue(LiveMic.takeBarredNotice(), "the ban's line is still owed");

        assertTrue(LiveMic.relayAllows(false), "banned again: a change again");
    }

    @Test
    void aStreamWithoutAMicTrackIgnoresTheRelaysWord() {
        LiveMic.startStream(false);
        assertFalse(LiveMic.relayAllows(false));
        assertFalse(LiveMic.barred());
        assertFalse(LiveMic.takeBarredNotice());
    }

    @Test
    void theNextStreamStartsUnbarred() {
        LiveMic.startStream(true);
        LiveMic.relayAllows(false);
        LiveMic.startStream(true);
        assertFalse(LiveMic.barred());
        assertFalse(LiveMic.takeBarredNotice(), "the old stream's line is dropped with it");
    }

    @Test
    void pushToTalkIsHeardOnlyWhileTheKeyIsHeld() {
        assertTrue(LiveMic.heard(true, false, false), "always on: the switch is enough");
        assertFalse(LiveMic.heard(true, true, false), "push to talk, key up: quiet");
        assertTrue(LiveMic.heard(true, true, true), "push to talk, key held: heard");
        assertFalse(LiveMic.heard(false, true, true), "the mic off: never heard, key or not");
        assertFalse(LiveMic.heard(false, false, false));
    }

    @Test
    void theHeartbeatReportsWhatIsHeardNotTheSwitch() {
        LiveMic.startStream(true);
        assertFalse(LiveMic.heartbeatMicOn(), "off at the start of every stream");
        LiveMic.setPushToTalkHeld(true);
        assertFalse(LiveMic.heartbeatMicOn(), "holding the key with the mic off is still off");
        assertEquals(LiveMic.transmitting(), LiveMic.heartbeatMicOn());
    }
}
