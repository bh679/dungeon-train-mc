package games.brennan.dungeontrain.client.policy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AI Policy's Explainers card points at posts on the website: each one must open a real
 * dungeon.training page over https (the link confirm screen shows the URL to the player), and its
 * title and description must exist in English so the card never shows a raw key.
 */
class AiPolicyContentTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyExplainerIsAnHttpsDungeonTrainingPageWithEnglishText() throws Exception {
        JsonObject en;
        try (Reader r = new InputStreamReader(Objects.requireNonNull(
                AiPolicyContentTest.class.getResourceAsStream("/assets/dungeontrain/lang/en_us.json")),
                StandardCharsets.UTF_8)) {
            en = JsonParser.parseReader(r).getAsJsonObject();
        }
        assertFalse(AiPolicyContent.explainers().isEmpty(), "the card has at least one explainer");
        assertTrue(en.has("gui.dungeontrain.ai_policy.explainers.header"));
        assertTrue(en.has("gui.dungeontrain.ai_policy.explainers.read"));
        for (AiPolicyContent.Explainer e : AiPolicyContent.explainers()) {
            URI url = URI.create(e.url());
            assertEquals("https", url.getScheme(), e.url());
            assertEquals("dungeon.training", url.getHost(), e.url());
            assertTrue(en.has(e.titleKey()), e.titleKey());
            assertTrue(en.has(e.descKey()), e.descKey());
            assertNotNull(e.glyph());
        }
    }
}
