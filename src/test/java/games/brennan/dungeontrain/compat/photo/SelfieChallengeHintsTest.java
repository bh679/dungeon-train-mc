package games.brennan.dungeontrain.compat.photo;

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
    @DisplayName("a selfie with only one boss names the one that's missing")
    void oneBossSelfie() {
        assertEquals(Optional.of(SelfieChallengeHints.MISSING_WITHER), SelfieChallengeHints.hintKey(true, true, false));
        assertEquals(Optional.of(SelfieChallengeHints.MISSING_WARDEN), SelfieChallengeHints.hintKey(true, false, true));
    }

    @Test
    @DisplayName("no hint for the winning shot, a plain selfie, or an ordinary photo of one boss")
    void quiet() {
        assertEquals(Optional.empty(), SelfieChallengeHints.hintKey(true, true, true));
        assertEquals(Optional.empty(), SelfieChallengeHints.hintKey(true, false, false));
        assertEquals(Optional.empty(), SelfieChallengeHints.hintKey(false, true, false));
        assertEquals(Optional.empty(), SelfieChallengeHints.hintKey(false, false, true));
        assertEquals(Optional.empty(), SelfieChallengeHints.hintKey(false, false, false));
    }
}
