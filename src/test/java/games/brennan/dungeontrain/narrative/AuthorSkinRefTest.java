package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthorSkinRefTest {

    private static final String URL = "http://textures.minecraft.net/texture/abc123";

    @Test
    void roundTripsSlimAndWide() {
        assertEquals(Optional.of(new AuthorSkinRef(URL, true)),
                AuthorSkinRef.decode(new AuthorSkinRef(URL, true).encode()));
        assertEquals(Optional.of(new AuthorSkinRef(URL, false)),
                AuthorSkinRef.decode(new AuthorSkinRef(URL, false).encode()));
        assertEquals("slim;" + URL, new AuthorSkinRef(URL, true).encode());
    }

    @Test
    void aNoteUploadedBeforeCaptureDecodesToNothing() {
        assertTrue(AuthorSkinRef.decode("").isEmpty());
        assertTrue(AuthorSkinRef.decode(null).isEmpty());
        assertTrue(AuthorSkinRef.decode("   ").isEmpty());
    }

    @Test
    void garbageIsNotTrusted() {
        assertTrue(AuthorSkinRef.decode(URL).isEmpty());          // bare url, no model
        assertTrue(AuthorSkinRef.decode("thin;" + URL).isEmpty()); // unknown model
        assertTrue(AuthorSkinRef.decode("slim;").isEmpty());       // blank url
        assertTrue(AuthorSkinRef.decode("playermob-profile:x;y").isEmpty());
    }
}
