package games.brennan.dungeontrain.client.live.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one mic switch (the Microphone option, stood in for here), the relay's mid-stream word on it,
 * and what the heartbeat reports as "on". No capture device is opened: the option stays off whenever a
 * switch-on would open one.
 */
class LiveMicTest {

    private final AtomicBoolean option = new AtomicBoolean();

    @BeforeEach
    void standInForTheOption() {
        LiveMic.setOptionForTests(option::get, option::set);
    }

    @AfterEach
    void endStream() {
        LiveMic.endStream();
        LiveMic.setOptionForTests(null, null);
        LiveMic.setMidStreamJoin(() -> LiveMic.Toggle.NEXT_STREAM);
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
        assertEquals(LiveMic.Toggle.BARRED, LiveMic.set(true, LiveMic.Source.KEY), "the switch does nothing while barred");
        assertFalse(option.get(), "and the option is not touched by the refused switch-on");

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
        assertFalse(LiveMic.heartbeatMicOn(), "the option is off, so the mic is");
        LiveMic.setPushToTalkHeld(true);
        assertFalse(LiveMic.heartbeatMicOn(), "holding the key with the mic off is still off");
        assertEquals(LiveMic.transmitting(), LiveMic.heartbeatMicOn());
    }

    @Test
    void theSwitchIsTheOptionAndTheStreamStartsAsItIsSet() {
        LiveMic.startStream(true);
        assertFalse(LiveMic.on(), "the option off: the mic starts this stream off");
        assertEquals(LiveMic.Toggle.OFF, LiveMic.set(false, LiveMic.Source.INVENTORY), "off again is just off");
        assertFalse(option.get());

        // Without a capture device a switch-on can only fail to open one; the option is still saved.
        assertEquals(LiveMic.Toggle.NO_DEVICE, LiveMic.toggle(LiveMic.Source.KEY));
        assertTrue(option.get(), "the key flipped the saved option");
        assertEquals(LiveMic.Toggle.OFF, LiveMic.toggle(LiveMic.Source.OPTIONS));
        assertFalse(option.get(), "and the option flipped it back");
    }

    @Test
    void switchedOnDuringAStreamWithoutAMicTrackTheOptionWaitsForTheNextStream() {
        LiveMic.startStream(false);
        assertEquals(LiveMic.Toggle.NEXT_STREAM, LiveMic.set(true, LiveMic.Source.OPTIONS));
        assertTrue(option.get(), "saved");
        assertFalse(LiveMic.on(), "but not on this stream: it carries no mic track");
        assertFalse(LiveMic.enabled());
        assertEquals(LiveMic.Toggle.OFF, LiveMic.set(false, LiveMic.Source.OPTIONS));
        assertFalse(option.get());
    }

    @Test
    void outsideAStreamTheSwitchOnlySavesTheOption() {
        assertEquals(LiveMic.Toggle.NEXT_STREAM, LiveMic.set(true, LiveMic.Source.OPTIONS));
        assertTrue(option.get());
        assertFalse(LiveMic.on());
        LiveMic.endStream();
        assertTrue(option.get(), "a stream ending leaves the option alone");
    }

    @Test
    void switchedOnMidStreamWhereATrackCanBeAddedTheStreamIsJoining() {
        LiveMic.startStream(false);
        LiveMic.setMidStreamJoin(() -> LiveMic.Toggle.JOINING);
        assertEquals(LiveMic.Toggle.JOINING, LiveMic.set(true, LiveMic.Source.KEY));
        assertTrue(option.get(), "saved before the track is added, so the new track opens the mic");
        assertFalse(LiveMic.heartbeatMicTrack(), "nothing told to the relay until the encoder writes the track");
    }

    @Test
    void aTrackAddedMidStreamIsSilentUntilTheRelayCountsIt() {
        option.set(true);
        LiveMic.startStream(false);
        LiveMic.joinMidStream();
        assertTrue(LiveMic.enabled());
        assertTrue(LiveMic.held());
        assertTrue(LiveMic.heartbeatMicTrack(), "the heartbeat now says the stream carries a mic track");
        assertFalse(LiveMic.on(), "held: Kid-mode viewers have not moved to the game-only track yet");
        assertFalse(LiveMic.heartbeatMicOn());

        assertFalse(LiveMic.relayTrack(LiveMic.TrackWord.NO), "not counted yet: still held");
        assertTrue(LiveMic.held());
        assertTrue(LiveMic.relayTrack(LiveMic.TrackWord.YES), "counted: the voice goes through");
        assertFalse(LiveMic.held());
        assertTrue(LiveMic.on());
        assertFalse(LiveMic.relayTrack(LiveMic.TrackWord.YES), "once");
        assertFalse(LiveMic.takeHeldNotice(), "nothing to say on a relay that counts it");
    }

    @Test
    void aRelayTooOldToCountTheTrackKeepsTheVoiceHeldAndSaysSoOnce() {
        option.set(true);
        LiveMic.startStream(false);
        LiveMic.joinMidStream();
        assertFalse(LiveMic.relayTrack(LiveMic.TrackWord.UNSAID));
        assertFalse(LiveMic.relayTrack(LiveMic.TrackWord.UNSAID));
        assertTrue(LiveMic.held(), "never heard on this stream");
        assertFalse(LiveMic.on());
        assertTrue(LiveMic.takeHeldNotice(), "the next-stream line, once");
        assertFalse(LiveMic.takeHeldNotice());
    }

    @Test
    void aStreamWithATrackFromTheStartIsNeverHeld() {
        option.set(true);
        LiveMic.startStream(true);
        assertFalse(LiveMic.held());
        assertFalse(LiveMic.relayTrack(LiveMic.TrackWord.UNSAID), "an older relay's silence is nothing to act on");
        assertFalse(LiveMic.takeHeldNotice());
        assertTrue(LiveMic.on());
    }

    @Test
    void theNextStreamDropsAHeldTrack() {
        option.set(true);
        LiveMic.startStream(false);
        LiveMic.joinMidStream();
        LiveMic.endStream();
        assertFalse(LiveMic.held());
        assertFalse(LiveMic.heartbeatMicTrack());
        LiveMic.startStream(false);
        assertFalse(LiveMic.held());
    }
}
