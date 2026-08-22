package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HeadProfiles}' pure halves — URL validation, the base64 {@code textures}
 * payload, and the derived uuid. Assembling the {@link net.minecraft.world.item.component.ResolvableProfile}
 * itself is left to the in-game Gate 2 check: it is a two-line wrapper over these, and exercising it
 * here would need a Forge bootstrap the rest of this source set deliberately avoids.
 */
final class HeadProfilesTest {

    private static final String URL =
        "http://textures.minecraft.net/texture/eb84f22489b6b5e426fc8c0c3b51daa002179e1c2e0767a552a22fc18d574d56";

    private static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("accepts a Mojang CDN texture URL over http and https")
    void isTextureUrl_acceptsMojangCdn() {
        assertTrue(HeadProfiles.isTextureUrl(URL));
        assertTrue(HeadProfiles.isTextureUrl(URL.replace("http://", "https://")));
    }

    @Test
    @DisplayName("rejects any other host, scheme, or shape")
    void isTextureUrl_rejectsEverythingElse() {
        assertFalse(HeadProfiles.isTextureUrl(null));
        assertFalse(HeadProfiles.isTextureUrl(""));
        // Not Mojang's CDN — a relay that returned this is wrong, not merely unlucky.
        assertFalse(HeadProfiles.isTextureUrl("http://example.com/texture/abcdef0123456789"));
        // Lookalike host: the dot in "textures.minecraft.net" must not match any character.
        assertFalse(HeadProfiles.isTextureUrl("http://texturesxminecraft.net/texture/abcdef0123456789"));
        // Subdomain prefix of the real host.
        assertFalse(HeadProfiles.isTextureUrl("http://evil.textures.minecraft.net/texture/abcdef0123456789"));
        // Right host, wrong path.
        assertFalse(HeadProfiles.isTextureUrl("http://textures.minecraft.net/evil/abcdef0123456789"));
        // Hash too short, and non-hex characters in the hash.
        assertFalse(HeadProfiles.isTextureUrl("http://textures.minecraft.net/texture/abc"));
        assertFalse(HeadProfiles.isTextureUrl("http://textures.minecraft.net/texture/zzzzzzzzzzzzzzzz"));
        // Trailing junk after an otherwise valid URL.
        assertFalse(HeadProfiles.isTextureUrl(URL + "?x=1"));
    }

    @Test
    @DisplayName("wide skin encodes the URL with no model metadata")
    void textureValue_wideOmitsMetadata() {
        String json = decode(HeadProfiles.textureValue(URL, false));
        assertEquals("{\"textures\":{\"SKIN\":{\"url\":\"" + URL + "\"}}}", json);
    }

    @Test
    @DisplayName("slim skin encodes the slim model metadata")
    void textureValue_slimCarriesMetadata() {
        String json = decode(HeadProfiles.textureValue(URL, true));
        assertEquals("{\"textures\":{\"SKIN\":{\"url\":\"" + URL + "\",\"metadata\":{\"model\":\"slim\"}}}}", json);
    }

    @Test
    @DisplayName("accepts plain Minecraft usernames as head titles")
    void isPlayerName_acceptsUsernames() {
        assertTrue(HeadProfiles.isPlayerName("Notch"));
        assertTrue(HeadProfiles.isPlayerName("a_Player_99"));
        assertTrue(HeadProfiles.isPlayerName("A"));
        assertTrue(HeadProfiles.isPlayerName("_".repeat(16)));
    }

    @Test
    @DisplayName("rejects anything vanilla's profile codec would reject")
    void isPlayerName_rejectsEverythingElse() {
        assertFalse(HeadProfiles.isPlayerName(null));
        assertFalse(HeadProfiles.isPlayerName(""));
        assertFalse(HeadProfiles.isPlayerName("two words"));
        // A legacy formatting code would otherwise colour the item name.
        assertFalse(HeadProfiles.isPlayerName("\u00a7cRed"));
        assertFalse(HeadProfiles.isPlayerName("<script>"));
        assertFalse(HeadProfiles.isPlayerName("a".repeat(17)));
    }

    @Test
    @DisplayName("uuid is stable per URL and differs between URLs")
    void stableId_isDeterministicPerUrl() {
        assertEquals(HeadProfiles.stableId(URL), HeadProfiles.stableId(URL));
        assertNotEquals(HeadProfiles.stableId(URL), HeadProfiles.stableId(URL.replace("eb84", "ab84")));
    }

    @Test
    @DisplayName("uuid is not the raw name-hash of the URL — it is namespaced")
    void stableId_isNamespaced() {
        assertNotEquals(java.util.UUID.nameUUIDFromBytes(URL.getBytes(StandardCharsets.UTF_8)),
                        HeadProfiles.stableId(URL));
    }
}
