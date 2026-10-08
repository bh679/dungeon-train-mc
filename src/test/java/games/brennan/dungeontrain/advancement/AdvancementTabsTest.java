package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The advancement tab split: which tab each advancement lands in, the tab copies that unlock
 * Train Explorer / Others / The Enchiridion / The Darkroom, and the promise that no advancement id
 * was renamed or dropped (players' earned progress is keyed on ids).
 */
class AdvancementTabsTest {

    private static final String DT = "dungeon_train/";
    private static final String TRAIN_EXPLORER = DT + "tab_train_explorer";
    private static final String OTHERS = DT + "tab_others";
    private static final String ENCHIRIDION = EnchiridionAdvancements.ROOT;
    private static final String DARKROOM = EnchiridionAdvancements.DARKROOM_ROOT;

    /** path → raw advancement JSON, for every Dungeon Train advancement in the datapack. */
    private static Map<String, JsonObject> advancements() throws IOException {
        Path dir = RepoPaths.advancements();
        Map<String, JsonObject> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String path = dir.relativize(f).toString().replace('\\', '/').replaceAll("\\.json$", "");
                out.put(path, JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
            }
        }
        return out;
    }

    private static String parentPath(JsonObject adv) {
        JsonElement p = adv.get("parent");
        return p == null ? null : p.getAsString().replaceFirst("^dungeontrain:", "");
    }

    /** The tab an advancement is drawn in: the root at the end of its parent chain. */
    private static String tabOf(Map<String, JsonObject> all, String path) {
        String at = path;
        for (int guard = 0; guard < 100; guard++) {
            String parent = parentPath(all.get(at));
            if (parent == null) return at;
            at = parent;
        }
        throw new AssertionError("parent cycle at " + path);
    }

    private static TabGateways.Layout shippedLayout() throws IOException {
        Path file = RepoPaths.resources().resolve("dungeontrain/advancement_tabs.json");
        return TabGateways.Layout.parse(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("no advancement that existed before the tab split was renamed or removed")
    void everyOldIdStillExists() throws IOException {
        Map<String, JsonObject> all = advancements();
        List<String> missing = new ArrayList<>();
        try (var in = AdvancementTabsTest.class.getResourceAsStream("/advancement/ids_before_tab_split.txt")) {
            assertNotNull(in, "id snapshot missing from test resources");
            for (String id : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!id.isBlank() && !all.containsKey(id.strip())) missing.add(id.strip());
            }
        }
        assertTrue(missing.isEmpty(), "advancements gone since the tab split (players would lose them): " + missing);
    }

    @Test
    @DisplayName("each advancement sits in the tab the editor layout put it in")
    void tabsMatchTheLayout() throws IOException {
        Map<String, JsonObject> all = advancements();
        Map<String, String> expected = Map.ofEntries(
                Map.entry(DT + "carts_100", DT + "root"),
                Map.entry(DT + "no_container_100", DT + "tab_challenges"),
                Map.entry(DT + "chests_100_unique", DT + "tab_challenges"),
                Map.entry(DT + "drift_left_behind", DT + "root"),
                Map.entry(DT + "secrete_menu", DT + "root"),
                Map.entry(DT + "im_not_alone", DT + "root"),
                Map.entry(DT + "carts_10000", TRAIN_EXPLORER),
                Map.entry(DT + "the_long_run", TRAIN_EXPLORER),
                Map.entry(DT + "reached_nether", TRAIN_EXPLORER),
                Map.entry(DT + "ridden_biomes_150", TRAIN_EXPLORER),
                Map.entry(DT + "help_i_cant_get_off", TRAIN_EXPLORER),
                Map.entry(DT + "echo_kill", OTHERS),
                Map.entry(DT + "encountered_1000_players", OTHERS),
                Map.entry(DT + "the_denamed", OTHERS),
                Map.entry(DT + "the_far_start", ENCHIRIDION),
                Map.entry(DT + "read_all_stories", ENCHIRIDION),
                Map.entry("enchiridion/say_cheese", DARKROOM),
                Map.entry("enchiridion/photo_stacks", DARKROOM),
                Map.entry("enchiridion/big_spender", DARKROOM));
        expected.forEach((path, tab) -> assertEquals(tab, tabOf(all, path), path));
    }

    @Test
    @DisplayName("the band journey hangs from Train Explorer, read from reached_nether's own parent")
    void bandChainAnchor() throws IOException {
        Map<ResourceLocation, JsonElement> loaded = new LinkedHashMap<>();
        advancements().forEach((path, json) -> loaded.put(ResourceLocation.fromNamespaceAndPath("dungeontrain", path), json));
        assertEquals("tab_train_explorer", BandAdvancements.anchorFrom(loaded));
        assertEquals(BandAdvancements.ANCHOR, BandAdvancements.anchorFrom(Map.of()), "no reached_nether → fallback");
    }

    @Test
    @DisplayName("every tab copy is a hidden, silent mirror of an advancement that exists")
    void copiesAreSilentMirrors() throws IOException {
        Map<String, JsonObject> all = advancements();
        TabGateways.Layout layout = shippedLayout();
        assertEquals(Set.of("dungeontrain:" + TRAIN_EXPLORER, "dungeontrain:" + OTHERS,
                "dungeontrain:" + DT + "gate_enchiridion", "dungeontrain:" + DT + "gate_darkroom"), layout.copies().keySet());
        layout.copies().forEach((copyId, originalId) -> {
            JsonObject copy = all.get(copyId.replaceFirst("^dungeontrain:", ""));
            JsonObject original = all.get(originalId.replaceFirst("^dungeontrain:", ""));
            assertNotNull(copy, copyId);
            assertNotNull(original, originalId + " (original of " + copyId + ")");
            JsonObject d = copy.getAsJsonObject("display");
            assertTrue(d.get("hidden").getAsBoolean(), copyId + " must be hidden so its tab stays locked");
            assertFalse(d.get("show_toast").getAsBoolean(), copyId + " must not toast");
            assertFalse(d.get("announce_to_chat").getAsBoolean(), copyId + " must not announce");
            for (JsonElement c : copy.getAsJsonObject("criteria").asMap().values()) {
                assertEquals("minecraft:impossible", c.getAsJsonObject().get("trigger").getAsString(), copyId);
            }
            assertEquals(original.getAsJsonObject("display").get("title"), d.get("title"), copyId + " title");
        });
    }

    @Test
    @DisplayName("tab names resolve to English, and the order names real tab roots")
    void tabNamesAndOrder() throws IOException {
        TabGateways.Layout layout = shippedLayout();
        JsonObject en = JsonParser.parseString(Files.readString(RepoPaths.langFile("en_us"))).getAsJsonObject();
        assertEquals("Train Explorer", en.get(layout.tabNames().get("dungeontrain:" + TRAIN_EXPLORER)).getAsString());
        assertEquals("Others", en.get(layout.tabNames().get("dungeontrain:" + OTHERS)).getAsString());
        Map<String, JsonObject> all = advancements();
        for (String root : layout.order()) {
            JsonObject adv = all.get(root.replaceFirst("^dungeontrain:", ""));
            assertNotNull(adv, root);
            assertFalse(adv.has("parent"), root + " is in the tab order but is not a tab root");
        }
    }

    @Test
    @DisplayName("a copy shows its original's (requirement-rewritten) title and description")
    void mirrorTextCopiesTheOriginal() {
        ResourceLocation original = ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/carts_100");
        ResourceLocation copy = ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/tab_train_explorer");
        JsonObject originalJson = JsonParser.parseString("{\"display\":{\"title\":{\"translate\":\"t\"},"
                + "\"description\":{\"translate\":\"d\",\"with\":[\"50\"]}}}").getAsJsonObject();
        JsonObject copyJson = JsonParser.parseString("{\"display\":{\"title\":{\"translate\":\"t\"},"
                + "\"description\":{\"translate\":\"d\"},\"hidden\":true}}").getAsJsonObject();
        Map<ResourceLocation, JsonElement> loaded = new LinkedHashMap<>();
        loaded.put(original, originalJson);
        loaded.put(copy, copyJson);
        TabGateways.Layout layout = new TabGateways.Layout(Map.of(copy.toString(), original.toString()), Map.of(), List.of());

        Map<ResourceLocation, JsonElement> out = TabGateways.mirrorText(loaded, layout);

        JsonObject d = out.get(copy).getAsJsonObject().getAsJsonObject("display");
        assertEquals("50", d.getAsJsonObject("description").getAsJsonArray("with").get(0).getAsString());
        assertTrue(d.get("hidden").getAsBoolean(), "everything else on the copy is kept");
        assertFalse(copyJson.getAsJsonObject("display").getAsJsonObject("description").has("with"), "input not mutated");
        assertSame(loaded, TabGateways.mirrorText(loaded, TabGateways.Layout.EMPTY));
    }

    @Test
    @DisplayName("the Everything Burrito set matches the editor's golden list (keeps capstone_rules.py honest)")
    void burritoSetMatchesGolden() throws IOException {
        List<String> required = new ArrayList<>();
        for (String path : advancements().keySet()) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("dungeontrain", path);
            if (CompletionistAdvancement.isRequiredId(id, Set.of())) required.add(id.toString());
        }
        required.sort(null);
        String golden;
        try (var in = AdvancementTabsTest.class.getResourceAsStream("/advancement/burrito_required.txt")) {
            assertNotNull(in, "golden list missing");
            golden = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        assertEquals(golden, String.join("\n", required),
                "regenerate src/test/resources/advancement/burrito_required.txt with test_apply.py --regen-golden "
                + "and check scripts/advancements/editor/capstone_rules.py still mirrors CompletionistAdvancement");
    }

    @Test
    @DisplayName("capstone overrides parse from the tabs file; Start Again follows the burrito unless set")
    void capstoneOverridesParse() {
        TabGateways.Layout l = TabGateways.Layout.parse(new StringReader(
                "{\"burrito\":{\"dungeontrain:dungeon_train/a\":false},\"startAgainReset\":{\"dungeontrain:dungeon_train/b\":true,\"x\":\"no\"},"
                + "\"copies\":{\"c\":\"src\"},\"unlockedBy\":{\"u\":\"src\"}}"));
        assertEquals(List.of("c", "u"), l.followersOf("src"), "copies and unlock links both follow their source");
        assertEquals(Map.of("dungeontrain:dungeon_train/a", false), l.burrito());
        assertEquals(Map.of("dungeontrain:dungeon_train/b", true), l.startAgainReset(), "non-boolean values are ignored");
        ResourceLocation carts = ResourceLocation.fromNamespaceAndPath("dungeontrain", DT + "carts_100");
        assertTrue(StartAgainAdvancement.isWiped(carts, true));
        assertFalse(StartAgainAdvancement.isWiped(carts, false));
        assertTrue(StartAgainAdvancement.isWiped(CompletionistAdvancement.ID, false), "the capstone is always cleared");
    }

    @Test
    @DisplayName("Dungeon Train Explored sits in Dungeon Train under The Enchiridion's gateway, granted by code")
    void trainExploredPlacement() throws IOException {
        Map<String, JsonObject> all = advancements();
        JsonObject adv = all.get(DT + "train_explored");
        assertNotNull(adv);
        assertEquals("dungeontrain:" + DT + "gate_enchiridion", adv.get("parent").getAsString());
        assertEquals(DT + "root", tabOf(all, DT + "train_explored"));
        for (JsonElement c : adv.getAsJsonObject("criteria").asMap().values()) {
            assertEquals("minecraft:impossible", c.getAsJsonObject().get("trigger").getAsString());
        }
        assertEquals(TrainExploredAdvancement.TAB_ROOT.getPath(), TRAIN_EXPLORER);
    }

    @Test
    @DisplayName("Explored skips only band advancements the layout never visits")
    void exploredSkipsUnreachableBands() {
        Set<String> reachable = Set.of(BandAdvancements.NETHER, BandAdvancements.VOID);
        ResourceLocation nether = ResourceLocation.fromNamespaceAndPath("dungeontrain", DT + BandAdvancements.NETHER);
        ResourceLocation stacks = ResourceLocation.fromNamespaceAndPath("dungeontrain", DT + BandAdvancements.STACKS);
        ResourceLocation carts = ResourceLocation.fromNamespaceAndPath("dungeontrain", DT + "carts_1000");
        assertFalse(TrainExploredAdvancement.isUnreachableBand(nether, reachable));
        assertTrue(TrainExploredAdvancement.isUnreachableBand(stacks, reachable));
        assertFalse(TrainExploredAdvancement.isUnreachableBand(carts, reachable), "not a band: always required");
    }

    @Test
    @DisplayName("Challenges: its own head, unlocked by Dungeon Train Explorer, holding the five challenge trees")
    void challengesTab() throws IOException {
        Map<String, JsonObject> all = advancements();
        String challenges = DT + "tab_challenges";
        TabGateways.Layout layout = shippedLayout();
        assertEquals("dungeontrain:" + DT + "carts_100", layout.unlockedBy().get("dungeontrain:" + challenges));
        assertEquals(List.of("dungeontrain:" + challenges), layout.followersOf("dungeontrain:" + DT + "carts_100").stream()
                .filter(f -> f.endsWith("tab_challenges")).toList());
        for (String path : List.of("chests_100_unique", "leather_over_diamond", "no_break_1000", "contained_loop", "pacifist_1000")) {
            assertEquals(challenges, tabOf(all, DT + path), path);
        }
        JsonObject d = all.get(challenges).getAsJsonObject("display");
        assertTrue(d.get("hidden").getAsBoolean(), "locked until Dungeon Train Explorer");
        assertFalse(CompletionistAdvancement.isRequiredId(
                ResourceLocation.fromNamespaceAndPath("dungeontrain", challenges), Set.of()), "follows its source, never counts");
    }

    @Test
    @DisplayName("copies never count towards the Everything Burrito")
    void copiesOutsideTheBurrito() throws IOException {
        for (String copy : shippedLayout().copies().keySet()) {
            assertFalse(CompletionistAdvancement.isRequiredId(ResourceLocation.parse(copy), Set.of()), copy);
        }
        assertTrue(CompletionistAdvancement.isRequiredId(
                ResourceLocation.fromNamespaceAndPath("dungeontrain", DT + "carts_100"), Set.of()), "the original still counts");
    }
}
