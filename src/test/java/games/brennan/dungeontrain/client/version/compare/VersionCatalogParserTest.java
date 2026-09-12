package games.brennan.dungeontrain.client.version.compare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins the two listing shapes the fetcher relies on; no network, no Minecraft. */
class VersionCatalogParserTest {

    private static final String MODRINTH = """
            [
              {"version_number": "0.843.0", "changelog": "### 0.843.0\\n\\n**Thing**", "date_published": "2026-09-11T18:54:00Z"},
              {"version_number": "v0.849.0", "changelog": "notes", "date_published": "2026-09-12T07:35:00Z"},
              {"version_number": "weird-build", "changelog": "ignored"},
              {"version_number": "0.828.0", "changelog": null, "date_published": "2026-09-09T19:23:00Z"}
            ]
            """;

    private static final String CURSEFORGE = """
            {"title": "Dungeon Train Pack", "files": [
              {"name": "dungeon-train-0.794.0.zip", "uploaded_at": "2026-09-04T19:25:00Z"},
              {"name": "dungeon-train-0.828.0.zip", "uploaded_at": "2026-09-10T14:34:56Z"},
              {"name": "something-else.zip", "uploaded_at": "2026-09-10T14:34:56Z"}
            ]}
            """;

    @Test
    @DisplayName("Modrinth: version_number with optional v, changelog kept, odd names dropped, newest first")
    void modrinth() {
        PlatformVersions v = VersionCatalogParser.parseModrinth(MODRINTH);
        assertEquals(Platform.MODRINTH, v.platform());
        assertEquals(3, v.entries().size());
        assertEquals("0.849.0", v.latest().orElseThrow().version().toString());
        assertEquals("0.843.0", v.entries().get(1).version().toString());
        assertEquals("### 0.843.0\n\n**Thing**", v.entries().get(1).changelog());
        assertNull(v.entries().get(2).changelog());
    }

    @Test
    @DisplayName("CurseForge mirror: version read from the file name, no changelog, newest first")
    void curseforge() {
        PlatformVersions v = VersionCatalogParser.parseCurseForge(CURSEFORGE);
        assertEquals(Platform.CURSEFORGE, v.platform());
        assertEquals(2, v.entries().size());
        assertEquals("0.828.0", v.latest().orElseThrow().version().toString());
        assertNull(v.latest().orElseThrow().changelog());
        assertEquals("2026-09-10T14:34:56Z", v.latest().orElseThrow().publishedIso());
    }

    @Test
    @DisplayName("the wrong shape is an error, not an empty listing")
    void wrongShape() {
        assertThrows(IllegalArgumentException.class, () -> VersionCatalogParser.parseModrinth("{}"));
        assertThrows(IllegalArgumentException.class, () -> VersionCatalogParser.parseCurseForge("[]"));
        assertThrows(IllegalArgumentException.class, () -> VersionCatalogParser.parseCurseForge("{\"files\": 3}"));
    }
}
