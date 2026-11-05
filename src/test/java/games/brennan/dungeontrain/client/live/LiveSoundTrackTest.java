package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.Mic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveSoundTrackTest {

    @Test
    void everyoneButKidModeHearsTheMix() {
        assertEquals(LiveSoundTrack.MIX, LiveSoundTrack.choose(new Mic(true, true, false), false));
        assertEquals(LiveSoundTrack.MIX, LiveSoundTrack.choose(new Mic(true, true, true), false));
        assertEquals(LiveSoundTrack.MIX, LiveSoundTrack.choose(null, false));
    }

    @Test
    void kidModeHearsOnlyAVoiceApprovedForKids() {
        assertEquals(LiveSoundTrack.GAME_ONLY, LiveSoundTrack.choose(new Mic(true, true, false), true), "voice, not approved");
        assertEquals(LiveSoundTrack.GAME_ONLY, LiveSoundTrack.choose(new Mic(true, false, false), true),
            "a mic track that is off now can open any moment: the game alone");
        assertEquals(LiveSoundTrack.MIX, LiveSoundTrack.choose(new Mic(true, true, true), true), "approved for kids");
    }

    @Test
    void kidModeFailsClosedOnARelayThatSaysNothingAboutKids() {
        assertEquals(LiveSoundTrack.GAME_ONLY, LiveSoundTrack.choose(new Mic(true, true), true), "the two-field form means not approved");
    }

    @Test
    void aStreamWithoutAMicTrackHasOnlyTheMix() {
        assertEquals(LiveSoundTrack.MIX, LiveSoundTrack.choose(new Mic(false, false, false), true));
        assertEquals(LiveSoundTrack.MIX, LiveSoundTrack.choose(null, true), "an older relay, no mic block");
    }

    @Test
    void theGameOnlyTrackIsMappedAsTheSoundAndTheMeterIsLeftOut() {
        List<String> a = LiveHlsSession.audioArgs(41234, 41235, LiveSoundTrack.GAME_ONLY);
        String joined = String.join(" ", a);
        assertTrue(joined.contains("-map 0:a:1 -vn -ac 1 -ar 24000 -f s16le tcp://127.0.0.1:41234"), joined);
        assertFalse(joined.contains("41235"), "no second output for the level meter: " + joined);
        assertFalse(joined.contains("0:a:0"), joined);
        assertEquals(LiveHlsSession.audioArgs(41234, 41235), LiveHlsSession.audioArgs(41234, 41235, LiveSoundTrack.MIX));
        assertTrue(List.of(LiveHlsSession.args("https://x/live.m3u8", 640, 360, false, true, 41234, 0, LiveSoundTrack.GAME_ONLY))
            .contains("0:a:1"));
    }

    @Test
    void aMissingGameOnlyTrackReadsAsMissingSound() {
        assertTrue(LiveHlsSession.audioMissing("Failed to set value '0:a:1' for option 'map': Invalid argument", LiveSoundTrack.GAME_ONLY));
        assertFalse(LiveHlsSession.audioMissing("Failed to set value '0:a:1' for option 'map': Invalid argument", LiveSoundTrack.MIX));
        assertFalse(LiveHlsSession.audioMissing("Failed to set value '0:a:0' for option 'map': Invalid argument", LiveSoundTrack.GAME_ONLY));
    }
}
