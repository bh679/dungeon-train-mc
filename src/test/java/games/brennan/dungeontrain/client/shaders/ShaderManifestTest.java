package games.brennan.dungeontrain.client.shaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the shipped shader manifest and the previews it names.
 *
 * <h2>Why</h2>
 * <p>The Shaders page is data plus nine images, and every way it can break is silent. A manifest
 * entry pointing off {@code cdn.modrinth.com} is a download the client refuses at runtime, and the
 * pack simply disappears from a page that still looks fine. A missing preview draws a placeholder
 * that reads as "this pack has no screenshot yet" rather than as a build that lost an asset. And a
 * preview is only worth having if it is the agreed size — the page contain-fits against fixed
 * dimensions, so an odd one out is silently letterboxed.</p>
 *
 * <p>None of that shows up in a screenshot of the page taken by someone who knows what it should
 * say. It shows up here, at build time, or not until a player reports it.</p>
 */
class ShaderManifestTest {

    private static final String MANIFEST = "src/main/resources/assets/dungeontrain/shader_menu/packs.json";
    private static final String PREVIEWS = "src/main/resources/assets/dungeontrain/textures/gui/shaders";

    /** Matches {@code ShaderPack.ALLOWED_HOST}; the client refuses anything else. */
    private static final String ALLOWED_PREFIX = "https://cdn.modrinth.com/";

    /** Matches {@code ShaderMenuScreen.PREVIEW_W/H} and what {@code pack-previews.py} writes. */
    private static final int PREVIEW_W = 854;
    private static final int PREVIEW_H = 480;

    /** SHA-512 is 128 hex characters. A truncated one fails every download at the last step. */
    private static final int SHA512_HEX_LENGTH = 128;

    /** Matches {@code ShaderPack.Performance} and {@code fetch-packs.py}'s PERF_TIERS. */
    private static final Set<String> PERF_TIERS =
            Set.of("very-light", "light", "moderate", "heavy");

    private static List<JsonObject> packs() throws IOException {
        Path file = RepoPaths.root().resolve(MANIFEST);
        assertTrue(Files.isRegularFile(file), MANIFEST + " is missing");
        JsonObject root = JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray array = root.getAsJsonArray("packs");
        List<JsonObject> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            out.add(array.get(i).getAsJsonObject());
        }
        return out;
    }

    @Test
    void everyPackIsCompleteAndFetchableFromTheAllowedHost() throws IOException {
        List<JsonObject> packs = packs();
        assertFalse(packs.isEmpty(), "the shader manifest lists no packs");

        Set<String> ids = new HashSet<>();
        for (JsonObject pack : packs) {
            String id = pack.get("id").getAsString();
            assertTrue(ids.add(id), "duplicate pack id: " + id);
            for (String field : List.of("name", "version", "author", "filename", "url", "sha512",
                    "page", "performance")) {
                assertFalse(pack.get(field).getAsString().isBlank(), id + " has a blank " + field);
            }
            assertTrue(pack.get("url").getAsString().startsWith(ALLOWED_PREFIX),
                    id + " is not fetchable: the client only downloads from " + ALLOWED_PREFIX);
            assertEquals(SHA512_HEX_LENGTH, pack.get("sha512").getAsString().length(),
                    id + " has a SHA-512 that is not 128 hex characters");
            assertTrue(pack.get("size").getAsLong() > 0, id + " has no download size");
            assertTrue(pack.get("filename").getAsString().endsWith(".zip"),
                    id + " does not name a zip");
            assertTrue(PERF_TIERS.contains(pack.get("performance").getAsString()),
                    id + " has an unknown performance tier — ShaderPack.Performance.of() would "
                            + "silently fall back to MODERATE");
        }
    }

    /**
     * Each ranking drives a sort order, and a tie makes that order arbitrary rather than wrong —
     * two packs swapping places between runs, with nothing on screen to say why.
     */
    @Test
    void everyRankingIsATotalOrder() throws IOException {
        List<JsonObject> packs = packs();
        for (String field : List.of("vanilla_rank", "mood_rank", "author_rank")) {
            Set<Integer> ranks = new HashSet<>();
            for (JsonObject pack : packs) {
                int rank = pack.get(field).getAsInt();
                assertTrue(rank >= 1 && rank <= packs.size(),
                        pack.get("id").getAsString() + " has a " + field + " outside 1.." + packs.size());
                assertTrue(ranks.add(rank), field + " " + rank + " is used twice");
            }
        }
    }

    /**
     * The "Shaders off" row's own screenshot. It is not a pack, so nothing in the manifest names it
     * and only this notices when a capture run leaves it behind — the row would silently fall back
     * to its text placeholder while the other nine showed photographs.
     */
    @Test
    void theShadersOffRowHasItsControlFrame() throws IOException {
        Path preview = RepoPaths.root().resolve(PREVIEWS).resolve("vanilla.png");
        assertTrue(Files.isRegularFile(preview),
                "no vanilla control preview — run scripts/shaders/sweep-all.sh --preview <world>");
        int[] size = pngSize(preview);
        assertEquals(PREVIEW_W, size[0], "the vanilla preview is the wrong width");
        assertEquals(PREVIEW_H, size[1], "the vanilla preview is the wrong height");
    }

    @Test
    void everyPackHasAPreviewAtTheSizeThePageExpects() throws IOException {
        Path dir = RepoPaths.root().resolve(PREVIEWS);
        for (JsonObject pack : packs()) {
            String id = pack.get("id").getAsString();
            Path preview = dir.resolve(id + ".png");
            assertTrue(Files.isRegularFile(preview),
                    "no preview for " + id + " — run scripts/shaders/pack-previews.py");
            int[] size = pngSize(preview);
            assertEquals(PREVIEW_W, size[0], id + "'s preview is the wrong width");
            assertEquals(PREVIEW_H, size[1], id + "'s preview is the wrong height");
        }
    }

    /** Width and height straight out of the PNG's IHDR — no image library needed for two ints. */
    private static int[] pngSize(Path file) throws IOException {
        byte[] header = new byte[24];
        try (var in = Files.newInputStream(file)) {
            assertEquals(header.length, in.readNBytes(header, 0, header.length),
                    file + " is too short to be a PNG");
        }
        return new int[] {readInt(header, 16), readInt(header, 20)};
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }
}
