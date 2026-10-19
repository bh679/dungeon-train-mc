package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.compat.photo.SelfieChallengeHints.Diagnosis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which chat nudge a photo earns toward the Warden-and-Wither selfie. */
final class SelfieChallengeHintsTest {

    @Test
    @DisplayName("both bosses, but not a selfie: it's not a selfie")
    void bothNotSelfie() {
        assertEquals(Optional.of(SelfieChallengeHints.NOT_SELFIE), SelfieChallengeHints.hintKey(false, true, true));
    }

    @Test
    @DisplayName("no not-selfie line for a selfie, or a photo short of a boss")
    void notSelfieQuiet() {
        assertEquals(Optional.empty(), SelfieChallengeHints.hintKey(true, true, true));
        assertEquals(Optional.empty(), SelfieChallengeHints.hintKey(true, true, false));
        assertEquals(Optional.empty(), SelfieChallengeHints.hintKey(false, true, false));
        assertEquals(Optional.empty(), SelfieChallengeHints.hintKey(false, false, true));
    }

    @Test
    @DisplayName("a mob not in the picture at all gets no line")
    void outOfPicture() {
        assertEquals(Diagnosis.NONE, SelfieChallengeHints.diagnose(false, true, false));
        assertEquals(Diagnosis.NONE, SelfieChallengeHints.diagnose(false, false, false));
    }

    @Test
    @DisplayName("too far wins over eyes, in Exposure's order")
    void tooFar() {
        assertEquals(Diagnosis.TOO_FAR, SelfieChallengeHints.diagnose(true, true, true));
        assertEquals(Diagnosis.TOO_FAR, SelfieChallengeHints.diagnose(true, true, false));
    }

    @Test
    @DisplayName("near enough but eyes out of frame: the eyes line; eyes in (blocked view): nothing")
    void eyes() {
        assertEquals(Diagnosis.EYES, SelfieChallengeHints.diagnose(true, false, false));
        assertEquals(Diagnosis.NONE, SelfieChallengeHints.diagnose(true, false, true));
    }

    @Test
    @DisplayName("each mob and reason has its own key")
    void keys() {
        assertEquals(Optional.of("chat.dungeontrain.selfie_hint.warden_too_far"),
                SelfieChallengeHints.diagnosisKey("warden", Diagnosis.TOO_FAR));
        assertEquals(Optional.of("chat.dungeontrain.selfie_hint.wither_eyes"),
                SelfieChallengeHints.diagnosisKey("wither", Diagnosis.EYES));
        assertEquals(Optional.empty(), SelfieChallengeHints.diagnosisKey("warden", Diagnosis.NONE));
    }
}
