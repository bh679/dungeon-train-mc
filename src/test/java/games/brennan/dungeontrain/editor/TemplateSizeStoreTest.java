package games.brennan.dungeontrain.editor;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.train.ContentsSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TemplateSizeStoreTest {

    @Test
    @DisplayName("A manifest parses to id → size; an unknown size is skipped, not fatal")
    void parse() {
        Map<String, ContentsSize> sizes = TemplateSizeStore.parse(new StringReader(
            "{\"schemaVersion\":1,\"sizes\":{\"portal\":\"half\",\"long\":\"FULL\",\"bad\":\"huge\"}}"), "test");
        assertEquals(Map.of("portal", ContentsSize.HALF, "long", ContentsSize.FULL), sizes);
    }

    @Test
    @DisplayName("A manifest with no sizes block is empty")
    void parseEmpty() {
        assertTrue(TemplateSizeStore.parse(new StringReader("{\"schemaVersion\":1}"), "test").isEmpty());
    }

    @Test
    @DisplayName("Written manifests round-trip through parse")
    void roundTrip() {
        Map<String, ContentsSize> in = Map.of("a", ContentsSize.FULL, "b", ContentsSize.HALF);
        JsonObject json = TemplateSizeStore.toJson(in);
        assertEquals(TemplateSizeStore.SCHEMA_VERSION, json.get("schemaVersion").getAsInt());
        assertEquals(in, TemplateSizeStore.parse(new StringReader(json.toString()), "test"));
    }

    @Test
    @DisplayName("The bundled manifests put the long portal corridor and its contents in Half")
    void bundledPortalIsHalf() throws Exception {
        for (String path : new String[] {"/data/dungeontrain/contents/sizes.json", "/data/dungeontrain/templates/sizes.json"}) {
            try (InputStream in = TemplateSizeStore.class.getResourceAsStream(path)) {
                assertNotNull(in, path);
                Map<String, ContentsSize> sizes =
                    TemplateSizeStore.parse(new InputStreamReader(in, StandardCharsets.UTF_8), path);
                assertEquals(ContentsSize.HALF, sizes.get("portal"), path);
                assertEquals(null, sizes.get("portal_short"), path + ": the short corridor is Room-sized");
            }
        }
    }
}
