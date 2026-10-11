package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.net.relay.BookAuthorsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which face an author room's heads wear. */
class PortalRoomAuthorHeadsTest {

    private static final String SKIN = "http://textures.minecraft.net/texture/" + "cd".repeat(32);

    private static String b64(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("The relay's skin is the author's face")
    void relaySkinWins() {
        BookAuthorsClient.Author a = new BookAuthorsClient.Author("p1", "Wren", 3, false, SKIN, true);
        assertEquals(Optional.of(new PortalRoomAuthorHeads.Skin(SKIN, true)), PortalRoomAuthorHeads.skinFor(a, null));
    }

    @Test
    @DisplayName("A stranger with no known skin leaves the heads alone")
    void noSkinNoFace() {
        assertTrue(PortalRoomAuthorHeads.skinFor(BookAuthorsClient.Author.other("p1", "Wren", 3), null).isEmpty());
    }

    @Test
    @DisplayName("A live textures property yields its SKIN url and model")
    void liveTexturesParse() {
        String slim = b64("{\"textures\":{\"SKIN\":{\"url\":\"" + SKIN + "\",\"metadata\":{\"model\":\"slim\"}}}}");
        assertEquals(Optional.of(new PortalRoomAuthorHeads.Skin(SKIN, true)), PortalRoomAuthorHeads.fromTextures(slim));
        String wide = b64("{\"textures\":{\"SKIN\":{\"url\":\"" + SKIN + "\"}}}");
        assertFalse(PortalRoomAuthorHeads.fromTextures(wide).orElseThrow().slim());
    }

    @Test
    @DisplayName("Garbage, foreign hosts and capes-only profiles give no face rather than throwing")
    void badTexturesAreEmpty() {
        assertTrue(PortalRoomAuthorHeads.fromTextures(null).isEmpty());
        assertTrue(PortalRoomAuthorHeads.fromTextures("!!not base64!!").isEmpty());
        assertTrue(PortalRoomAuthorHeads.fromTextures(b64("[]")).isEmpty());
        assertTrue(PortalRoomAuthorHeads.fromTextures(b64("{\"textures\":{\"CAPE\":{\"url\":\"" + SKIN + "\"}}}")).isEmpty());
        assertTrue(PortalRoomAuthorHeads.fromTextures(
            b64("{\"textures\":{\"SKIN\":{\"url\":\"https://evil.example/s.png\"}}}")).isEmpty());
    }
}
