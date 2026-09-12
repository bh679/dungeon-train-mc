package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules for carrying loot prefabs with a build, against a fake library.
 *
 * <p>Pinned because the two failure modes are silent: a prefab that should have travelled and did
 * not leaves a downloaded build's chests empty on the other machine, and a prefab that should have
 * asked and did not silently changes the loot of every local template sharing its id.</p>
 */
final class TemplateLootPrefabsTest {

    private static String prefab(String block, int count, int weight) {
        return "{\"schemaVersion\":4,\"block\":\"" + block + "\",\"category\":\"loot\",\"fillMin\":0,"
                + "\"fillMax\":-1,\"entries\":[{\"id\":\"minecraft:gold_ingot\",\"count\":" + count
                + ",\"weight\":" + weight + "}]}";
    }

    /** A library of texts: config-tier ids in {@code config}, bundled-only ids in {@code bundled}. */
    private static final class FakeLibrary implements TemplateLootPrefabs.Library {
        final Map<String, String> config = new TreeMap<>();
        final Map<String, String> bundled = new TreeMap<>();
        final List<String> written = new java.util.ArrayList<>();
        final Set<String> failing = new java.util.HashSet<>();

        @Override public Optional<String> localText(String id) {
            if (config.containsKey(id)) return Optional.of(config.get(id));
            return Optional.ofNullable(bundled.get(id));
        }

        @Override public boolean hasConfigFile(String id) {
            return config.containsKey(id);
        }

        @Override public void write(String id, String text) throws IOException {
            if (failing.contains(id)) throw new IOException("disk full");
            config.put(id, text);
            written.add(id);
        }
    }

    // ---- collect ----

    @Test
    @DisplayName("only config-tier prefabs travel — a bundled one is on every install already")
    void collectCarriesConfigTierOnly() {
        FakeLibrary lib = new FakeLibrary();
        lib.config.put("gold", prefab("minecraft:chest", 3, 10));
        lib.bundled.put("treasure", prefab("minecraft:chest", 1, 1));
        Map<String, String> out = TemplateLootPrefabs.textsOf(List.of("treasure", "gold", "missing"), lib, "vault");
        assertEquals(Map.of("gold", prefab("minecraft:chest", 3, 10)), out);
    }

    @Test
    @DisplayName("a linked id that is not a valid prefab name is skipped rather than sent")
    void collectSkipsInvalidIds() {
        FakeLibrary lib = new FakeLibrary();
        lib.config.put("gold", prefab("minecraft:chest", 3, 10));
        Map<String, String> out = TemplateLootPrefabs.textsOf(List.of("Bad Id", "gold", ""), lib, "vault");
        assertEquals(Set.of("gold"), out.keySet());
    }

    @Test
    @DisplayName("collect caps at MAX_PER_BUILD and encode caps the document")
    void collectAndEncodeAreBounded() {
        FakeLibrary lib = new FakeLibrary();
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < TemplateLootPrefabs.MAX_PER_BUILD + 5; i++) {
            String id = String.format("p%03d", i);
            ids.add(id);
            lib.config.put(id, prefab("minecraft:chest", i, 1));
        }
        assertEquals(TemplateLootPrefabs.MAX_PER_BUILD, TemplateLootPrefabs.textsOf(ids, lib, "vault").size());

        Map<String, String> huge = new LinkedHashMap<>();
        huge.put("big", "x".repeat(TemplateLootPrefabs.MAX_DOC_CHARS + 1));
        assertEquals("", TemplateLootPrefabs.encode(huge), "over the cap the build uploads without prefabs");
        assertEquals("", TemplateLootPrefabs.encode(Map.of()), "nothing linked says nothing");
    }

    @Test
    @DisplayName("encode and decode are inverses, and decode shrugs at anything else")
    void encodeDecodeRoundTrip() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("gold", prefab("minecraft:chest", 3, 10));
        in.put("silver", prefab("minecraft:barrel", 1, 1));
        String doc = TemplateLootPrefabs.encode(in);
        assertEquals(in, TemplateLootPrefabs.decode(doc));
        assertEquals(in, TemplateLootPrefabs.decode(JsonParser.parseString(doc)));

        assertTrue(TemplateLootPrefabs.decode("").isEmpty(), "a relay that says nothing installs nothing");
        assertTrue(TemplateLootPrefabs.decode("not json").isEmpty());
        assertTrue(TemplateLootPrefabs.decode("[1,2]").isEmpty());
        assertEquals(Set.of("gold"), TemplateLootPrefabs.decode(
                "{\"gold\":\"{}\",\"Bad Id\":\"{}\",\"blank\":\"  \",\"num\":5}").keySet(),
                "ids that could never be prefab names, blanks and non-text are dropped");
        assertEquals(Set.of("gold"), TemplateLootPrefabs.decode("{\"GOLD\":\"{}\"}").keySet(),
                "ids are lowercased like the store lowercases them");
    }

    // ---- conflicts ----

    @Test
    @DisplayName("a conflict is an id already here whose parsed contents differ — nothing else")
    void conflictsAreDifferingIdsOnly() {
        FakeLibrary lib = new FakeLibrary();
        lib.config.put("same", prefab("minecraft:chest", 3, 10));
        lib.config.put("differs", prefab("minecraft:chest", 3, 10));
        lib.bundled.put("bundled_differs", prefab("minecraft:chest", 1, 1));
        Map<String, String> incoming = new LinkedHashMap<>();
        // Same prefab, spelled differently: key order and whitespace must not count as a change.
        incoming.put("same", "{ \"entries\": [ {\"weight\": 10, \"count\": 3, \"id\": \"minecraft:gold_ingot\"} ],"
                + " \"block\": \"minecraft:chest\", \"schemaVersion\": 4 }");
        incoming.put("differs", prefab("minecraft:chest", 9, 10));
        incoming.put("bundled_differs", prefab("minecraft:barrel", 1, 1));
        incoming.put("brand_new", prefab("minecraft:chest", 1, 1));

        List<TemplateLootPrefabs.Conflict> conflicts = TemplateLootPrefabs.conflicts(incoming, lib);
        assertEquals(List.of("differs", "bundled_differs"),
                conflicts.stream().map(TemplateLootPrefabs.Conflict::id).toList(),
                "identical-as-parsed and absent ids never ask; a bundled prefab is still a local prefab");
        assertEquals(prefab("minecraft:chest", 3, 10), conflicts.get(0).localText());
        assertEquals(prefab("minecraft:chest", 9, 10), conflicts.get(0).incomingText());
    }

    @Test
    @DisplayName("an incoming prefab that will not parse is a conflict when a local one exists")
    void unparseableIncomingStillAsks() {
        FakeLibrary lib = new FakeLibrary();
        lib.config.put("gold", prefab("minecraft:chest", 3, 10));
        assertEquals(1, TemplateLootPrefabs.conflicts(Map.of("gold", "not json"), lib).size(),
                "the player still decides; the screen just cannot show that side");
        assertTrue(TemplateLootPrefabs.sameParsed("gold", prefab("minecraft:chest", 1, 1), prefab("minecraft:chest", 1, 1)));
        assertFalse(TemplateLootPrefabs.sameParsed("gold", "x", prefab("minecraft:chest", 1, 1)));
    }

    // ---- install ----

    @Test
    @DisplayName("install writes what is missing plus what the player chose, and leaves the rest alone")
    void installHonoursTheAnswer() {
        FakeLibrary lib = new FakeLibrary();
        lib.config.put("keep", prefab("minecraft:chest", 1, 1));
        lib.config.put("replace", prefab("minecraft:chest", 1, 1));
        lib.bundled.put("bundled_keep", prefab("minecraft:chest", 1, 1));
        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("keep", prefab("minecraft:chest", 9, 9));
        incoming.put("replace", prefab("minecraft:chest", 9, 9));
        incoming.put("bundled_keep", prefab("minecraft:barrel", 9, 9));
        incoming.put("fresh", prefab("minecraft:chest", 5, 5));

        List<String> written = TemplateLootPrefabs.install(incoming, Set.of("replace"), Map.of(), lib);
        assertEquals(List.of("replace", "fresh"), written);
        assertEquals(prefab("minecraft:chest", 1, 1), lib.config.get("keep"), "keep mine means keep mine");
        assertEquals(prefab("minecraft:chest", 9, 9), lib.config.get("replace"));
        assertEquals(prefab("minecraft:chest", 5, 5), lib.config.get("fresh"));
        assertFalse(lib.config.containsKey("bundled_keep"), "a bundled prefab not chosen is not shadowed");
    }

    @Test
    @DisplayName("one prefab that will not write does not stop the others")
    void installStepsOverFailures() {
        FakeLibrary lib = new FakeLibrary();
        lib.failing.add("broken");
        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("broken", prefab("minecraft:chest", 1, 1));
        incoming.put("fine", prefab("minecraft:chest", 1, 1));
        assertEquals(List.of("fine"), TemplateLootPrefabs.install(incoming, Set.of(), Map.of(), lib));
    }

    @Test
    @DisplayName("a rename files their version under the new name and leaves the old id alone")
    void installRenames() {
        FakeLibrary lib = new FakeLibrary();
        lib.config.put("gold", prefab("minecraft:chest", 1, 1));
        lib.config.put("taken", prefab("minecraft:chest", 2, 2));
        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("gold", prefab("minecraft:chest", 9, 9));
        incoming.put("silver", prefab("minecraft:chest", 5, 5));

        List<String> written = TemplateLootPrefabs.install(incoming, Set.of(),
                Map.of("gold", "gold_relay", "silver", "taken"), lib);
        assertEquals(List.of("gold_relay"), written,
                "gold lands under its new name; silver's new name is already a prefab here and is refused");
        assertEquals(prefab("minecraft:chest", 1, 1), lib.config.get("gold"), "the local gold is untouched");
        assertEquals(prefab("minecraft:chest", 9, 9), lib.config.get("gold_relay"));
        assertEquals(prefab("minecraft:chest", 2, 2), lib.config.get("taken"), "never renamed onto an existing prefab");
    }

    // ---- the store's text-level parse ----

    @Test
    @DisplayName("LootPrefabStore.parse reads a v4 file and a v1 file, and refuses garbage")
    void storeParse() {
        Optional<LootPrefabStore.Data> v4 = LootPrefabStore.parse("gold", prefab("minecraft:barrel", 3, 10));
        assertTrue(v4.isPresent());
        assertEquals("minecraft:barrel", v4.get().sourceBlock().toString());
        assertEquals(1, v4.get().pool().entries().size());
        assertEquals(3, v4.get().pool().entries().get(0).count());

        Optional<LootPrefabStore.Data> v1 = LootPrefabStore.parse("old",
                "{\"schemaVersion\":1,\"entries\":[{\"id\":\"minecraft:wheat\",\"count\":5,\"weight\":10}]}");
        assertTrue(v1.isPresent(), "a file from before `block` existed still reads");
        assertEquals("minecraft:chest", v1.get().sourceBlock().toString(), "…and falls back to a chest");
        assertEquals(LootPrefabStore.CATEGORY_LOOT, v1.get().category());

        assertTrue(LootPrefabStore.parse("x", "not json").isEmpty());
        assertTrue(LootPrefabStore.parse("x", "[1]").isEmpty());
        assertTrue(LootPrefabStore.parse("x", "").isEmpty());
        assertTrue(LootPrefabStore.parse(null, "{}").isEmpty());
    }
}
