package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Enchiridion tab: which advancements are on it, and that the burrito and its reset leave them be. */
final class EnchiridionAdvancementsTest {

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath("dungeontrain", path);
    }

    /** Every shipped dungeontrain advancement path → its parent path (null for a tab root). */
    private static Map<String, String> parents() throws IOException {
        Path dir = RepoPaths.advancements();
        Map<String, String> out = new HashMap<>();
        try (Stream<Path> tree = Files.walk(dir)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".json")).toList()) {
                String path = dir.relativize(file).toString().replace('\\', '/');
                path = path.substring(0, path.length() - ".json".length());
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                String parent = json.has("parent")
                        ? ResourceLocation.parse(json.get("parent").getAsString()).getPath() : null;
                out.put(path, parent);
            }
        }
        return out;
    }

    private static String tabRoot(Map<String, String> parents, String path) {
        String at = path;
        for (int i = 0; i < 200 && parents.get(at) != null; i++) at = parents.get(at);
        return at;
    }

    @Test
    @DisplayName("isEnchiridion matches exactly the advancements whose chain ends at The Enchiridion or The Darkroom")
    void setMatchesTheTree() throws IOException {
        Map<String, String> parents = parents();
        List<String> wrong = new ArrayList<>();
        // Tab copies (the in-tab Hero's Handbook and Fully Developed) are mirrors that never count on their own.
        Set<String> copies = TabGateways.layout().copies().keySet();
        for (String path : parents.keySet()) {
            if (copies.contains("dungeontrain:" + path)) continue;
            String tab = tabRoot(parents, path);
            boolean onTab = EnchiridionAdvancements.ROOT.equals(tab) || EnchiridionAdvancements.DARKROOM_ROOT.equals(tab);
            if (onTab != EnchiridionAdvancements.isEnchiridion(path)) wrong.add(path + (onTab ? " (on tab)" : " (off tab)"));
        }
        assertTrue(wrong.isEmpty(), "isEnchiridion disagrees with the JSON tree: " + wrong);
        for (String book : EnchiridionAdvancements.BOOK_PATHS) {
            assertTrue(parents.containsKey(book), "BOOK_PATHS names a missing advancement: " + book);
        }
    }

    @Test
    @DisplayName("The Enchiridion and The Darkroom are tab roots")
    void rootIsATab() throws IOException {
        assertNull(parents().get(EnchiridionAdvancements.ROOT));
        assertNull(parents().get(EnchiridionAdvancements.DARKROOM_ROOT));
    }

    @Test
    @DisplayName("not required by the burrito, so the start-again wipe keeps it")
    void outsideTheBurrito() {
        for (String path : List.of(EnchiridionAdvancements.ROOT, "dungeon_train/taking_notes",
                "dungeon_train/nothing_but_books", "enchiridion/say_cheese", "enchiridion/photo_stacks")) {
            assertFalse(CompletionistAdvancement.isRequiredId(rl(path), Set.of(), true), path);
            assertFalse(StartAgainAdvancement.isWiped(rl(path), false), path);
        }
    }

    @Test
    @DisplayName("Respect The Rules is on the Challenges tab, not The Enchiridion, and still counts")
    void chestsStillRequired() throws IOException {
        assertEquals("dungeon_train/tab_challenges", tabRoot(parents(), "dungeon_train/chests_100_unique"));
        assertTrue(CompletionistAdvancement.isRequiredId(rl("dungeon_train/chests_100_unique"), Set.of(), true), "counts were it in the Dungeon Train tab");
    }

    @Test
    @DisplayName("every dimension has a photo advancement, chained in dimension order from Hellish Holiday")
    void dimensionPhotoChain() throws IOException {
        Map<String, String> parents = parents();
        String expectedParent = EnchiridionAdvancements.PATH_PREFIX + "say_cheese";
        for (String band : BandAdvancements.ALL) {
            String photo = EnchiridionAdvancements.PATH_PREFIX + EnchiridionAdvancements.photoName(band);
            assertTrue(parents.containsKey(photo), "no photo advancement for " + band + " (" + photo + ")");
            assertEquals(expectedParent, parents.get(photo), photo + " is out of the chain");
            expectedParent = photo;
        }
        assertEquals("hellish_holiday", EnchiridionAdvancements.photoName(BandAdvancements.ALL.get(0)));
        assertEquals("end_credits", EnchiridionAdvancements.photoName(BandAdvancements.END_ISLANDS));
        assertEquals("photo_upside_down", EnchiridionAdvancements.photoName(BandAdvancements.UPSIDE_DOWN));
    }

    @Test
    @DisplayName("the Enchiridion tab is drawn as a frontier, like Dungeon Train")
    void frontierTab() {
        assertTrue(BandAdvancements.isFrontierTab("enchiridion/say_cheese"));
    }

    @Test
    @DisplayName("collections keep an album; the biome tiers share one; everything else has none")
    void albums() throws IOException {
        Map<String, String> parents = parents();
        assertEquals("enchiridion/nature_documentary", EnchiridionAdvancements.albumOf("enchiridion/nature_documentary"));
        assertEquals("enchiridion/most_wanted", EnchiridionAdvancements.albumOf("enchiridion/most_wanted"));
        for (String tier : List.of("enchiridion/scenic_route", "enchiridion/travel_brochure", "enchiridion/coffee_table_book")) {
            assertTrue(parents.containsKey(tier), tier);
            assertEquals(EnchiridionAdvancements.BIOME_ALBUM, EnchiridionAdvancements.albumOf(tier));
        }
        assertTrue(EnchiridionAdvancements.isBiomeAlbum(EnchiridionAdvancements.BIOME_ALBUM));
        assertNull(EnchiridionAdvancements.albumOf("enchiridion/say_cheese"));
        assertNull(EnchiridionAdvancements.albumOf(null));
    }

    @Test
    @DisplayName("the album's biome targets match the tiers' JSON thresholds")
    void biomeTargets() throws IOException {
        for (Map.Entry<String, Integer> t : EnchiridionAdvancements.BIOME_TIER_TARGETS.entrySet()) {
            JsonObject json = JsonParser.parseString(Files.readString(
                    RepoPaths.advancements().resolve(t.getKey() + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(t.getValue().intValue(), json.getAsJsonObject("criteria").getAsJsonObject("biomes")
                    .getAsJsonObject("conditions").get("threshold").getAsInt(), t.getKey());
        }
    }
}
