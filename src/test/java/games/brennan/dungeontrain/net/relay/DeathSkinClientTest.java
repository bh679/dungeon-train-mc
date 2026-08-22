package games.brennan.dungeontrain.net.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DeathSkinClient#parse} — the wire boundary. Everything here is data off the
 * network on its way into a block entity, so the parser's job is as much rejection as it is reading:
 * a malformed body must yield null (no callback, heads keep their fallback skins) and a bad entry
 * must be dropped rather than turned into an invisible head.
 */
final class DeathSkinClientTest {

    private static final String URL_A = "http://textures.minecraft.net/texture/"
        + "eb84f22489b6b5e426fc8c0c3b51daa002179e1c2e0767a552a22fc18d574d56";
    private static final String URL_B = "https://textures.minecraft.net/texture/"
        + "ff7747ea340989b98a8222948af45454bee909b9ad830b2a01dcf9117c83a33a";

    @Test
    @DisplayName("reads url + model from a well-formed response")
    void parse_readsSkins() {
        List<DeathSkinClient.Skin> out = DeathSkinClient.parse(
            "{\"ok\":true,\"carriage\":3,\"skins\":[{\"url\":\"" + URL_A + "\",\"model\":\"wide\"},"
            + "{\"url\":\"" + URL_B + "\",\"model\":\"slim\"}]}");
        assertEquals(List.of(new DeathSkinClient.Skin(URL_A, false, ""),
                             new DeathSkinClient.Skin(URL_B, true, "")), out);
    }

    @Test
    @DisplayName("an empty skins array is a valid answer, not a failure")
    void parse_emptyListIsValid() {
        List<DeathSkinClient.Skin> out = DeathSkinClient.parse("{\"ok\":true,\"carriage\":9,\"skins\":[]}");
        assertEquals(List.of(), out);
    }

    @Test
    @DisplayName("missing model defaults to the wide arm variant")
    void parse_defaultsToWide() {
        List<DeathSkinClient.Skin> out = DeathSkinClient.parse(
            "{\"ok\":true,\"skins\":[{\"url\":\"" + URL_A + "\"}]}");
        assertEquals(List.of(new DeathSkinClient.Skin(URL_A, false, "")), out);
    }

    @Test
    @DisplayName("reads the wearer's name, which is what titles the mined head")
    void parse_readsName() {
        List<DeathSkinClient.Skin> out = DeathSkinClient.parse(
            "{\"ok\":true,\"skins\":[{\"url\":\"" + URL_A + "\",\"name\":\"Ranboo\"}]}");
        assertEquals(List.of(new DeathSkinClient.Skin(URL_A, false, "Ranboo")), out);
    }

    @Test
    @DisplayName("a name vanilla's profile codec would reject leaves the head untitled, not unskinned")
    void parse_dropsUnusableNames() {
        // The whole encode fails on a bad name, so the skin must survive it being dropped.
        List<DeathSkinClient.Skin> out = DeathSkinClient.parse(
            "{\"ok\":true,\"skins\":[{\"url\":\"" + URL_A + "\",\"name\":\"two words\"},"
            + "{\"url\":\"" + URL_B + "\",\"name\":\"\u00a7cRed\"}]}");
        assertEquals(List.of(new DeathSkinClient.Skin(URL_A, false, ""),
                             new DeathSkinClient.Skin(URL_B, false, "")), out);
    }

    @Test
    @DisplayName("drops entries whose URL is not a Mojang CDN skin")
    void parse_dropsForeignUrls() {
        List<DeathSkinClient.Skin> out = DeathSkinClient.parse(
            "{\"ok\":true,\"skins\":[{\"url\":\"http://evil.example/texture/abcdef0123456789\"},"
            + "{\"url\":\"\"},{\"url\":\"" + URL_A + "\"},{\"nope\":1}]}");
        assertEquals(List.of(new DeathSkinClient.Skin(URL_A, false, "")), out);
    }

    @Test
    @DisplayName("null for a body that is not an ok response with a skins array")
    void parse_rejectsMalformedBodies() {
        assertNull(DeathSkinClient.parse("{\"ok\":false,\"skins\":[]}"));
        assertNull(DeathSkinClient.parse("{\"ok\":true}"));
        assertNull(DeathSkinClient.parse("{\"ok\":true,\"skins\":{}}"));
        assertNull(DeathSkinClient.parse("[]"));
        assertNull(DeathSkinClient.parse("\"a string\""));
    }

    @Test
    @DisplayName("caps a hostile response rather than filling the pool from it")
    void parse_capsRunawayResponses() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"skins\":[");
        for (int i = 0; i < 500; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"url\":\"").append(URL_A).append("\"}");
        }
        sb.append("]}");
        List<DeathSkinClient.Skin> out = DeathSkinClient.parse(sb.toString());
        assertTrue(out.size() <= 64, "expected the parser to cap the list, got " + out.size());
    }
}
