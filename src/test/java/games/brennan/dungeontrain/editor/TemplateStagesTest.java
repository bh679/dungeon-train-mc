package games.brennan.dungeontrain.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.template.Stage;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure halves of {@link TemplateStages}: what a save sends, what a download asks about, and
 * what it then writes. The store-backed wrappers are one call each and are exercised in-game.
 */
final class TemplateStagesTest {

    private static final TemplateGate SWAMP_GATE = new TemplateGate(20, 40, EnumSet.of(TrainPhase.OVERWORLD, TrainPhase.VOID));
    private static final Stage SWAMP = new Stage("swamp", "swamp", SWAMP_GATE);
    private static final Stage DESERT = new Stage("desert", "desert", new TemplateGate(11, 35, null));

    private static String json(Stage s) {
        return StageStore.toJsonText(s);
    }

    // ---- upload ----

    @Test
    @DisplayName("the library encodes as an id → json-text object, sorted, and round-trips through parseJsonText")
    void encodeLibraryRoundTrips() {
        Map<String, Stage> authored = new LinkedHashMap<>();
        authored.put("swamp", SWAMP);
        authored.put("desert", DESERT);
        String doc = TemplateStages.encodeLibrary(authored);
        JsonObject o = JsonParser.parseString(doc).getAsJsonObject();
        assertEquals(List.of("desert", "swamp"), List.copyOf(o.keySet()), "sorted for a stable wire text");
        Map<String, String> back = TemplateStages.decode(o);
        assertEquals(SWAMP, StageStore.parseJsonText("swamp", back.get("swamp")));
        assertEquals(DESERT, StageStore.parseJsonText("desert", back.get("desert")));
    }

    @Test
    @DisplayName("no authored stages → null, which the relay reads as 'said nothing'")
    void emptyLibraryIsNull() {
        assertNull(TemplateStages.encodeLibrary(Map.of()));
        assertNull(TemplateStages.encodeLibrary(null));
    }

    @Test
    @DisplayName("linked ids = the top-level link plus every part-entry link, deduped and lowercased")
    void linkedIdsDedupe() {
        CarriagePartAssignment parts = new CarriagePartAssignment(
                List.of(entry("floor_a", "Swamp"), entry("floor_b", null)),
                List.of(entry("wall_a", "desert"), entry("wall_b", "swamp")),
                List.of(), List.of());
        assertEquals(List.of("stone", "swamp", "desert"),
                TemplateStages.linkedIds("Stone", Optional.of(parts)));
        assertEquals(List.of("stone"), TemplateStages.linkedIds("stone", Optional.empty()));
        assertTrue(TemplateStages.linkedIds("", Optional.empty()).isEmpty(), "an unlinked build links nothing");
        assertTrue(TemplateStages.linkedIds(null, Optional.empty()).isEmpty());
    }

    private static CarriagePartAssignment.WeightedName entry(String name, String stageId) {
        return new CarriagePartAssignment.WeightedName(name, 1, CarriagePartAssignment.SideMode.BOTH,
                CarriagePartAssignment.EndMode.BOTH, TemplateGate.DEFAULT, stageId);
    }

    // ---- download ----

    @Test
    @DisplayName("decode keeps only string-valued entries and lowercases ids")
    void decodeTolerant() {
        JsonObject o = new JsonObject();
        o.addProperty("Swamp", json(SWAMP));
        o.addProperty("bad", 12);
        o.add("nul", null);
        Map<String, String> got = TemplateStages.decode(o);
        assertEquals(Set.of("swamp"), got.keySet());
        assertTrue(TemplateStages.decode(null).isEmpty());
    }

    @Test
    @DisplayName("conflicts = same id, different settings; identical or missing locally is no conflict")
    void conflictsOnlyWhereDifferent() {
        Map<String, Stage> local = Map.of(
                "swamp", SWAMP,
                "desert", DESERT.withGate(new TemplateGate(11, 60, null)));
        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("swamp", json(SWAMP));            // identical → not asked
        incoming.put("desert", json(DESERT));          // differs → asked
        incoming.put("copper", json(SWAMP.withName("copper"))); // missing here → just installs
        incoming.put("broken", "not json");            // unreadable → cannot install, nothing to ask
        List<TemplateStages.Conflict> got = TemplateStages.conflicts(incoming,
                id -> Optional.ofNullable(local.get(id)));
        assertEquals(1, got.size());
        assertEquals("desert", got.get(0).id());
        assertEquals(json(local.get("desert")), got.get(0).localJson());
        assertEquals(json(DESERT), got.get(0).incomingJson());
    }

    @Test
    @DisplayName("a renamed stage with the same gate is still a conflict — the name is what the picker shows")
    void renameIsAConflict() {
        Map<String, String> incoming = Map.of("swamp", json(SWAMP.withName("Marsh")));
        List<TemplateStages.Conflict> got = TemplateStages.conflicts(incoming, id -> Optional.of(SWAMP));
        assertEquals(1, got.size());
    }

    @Test
    @DisplayName("install writes the ids missing here plus the chosen overwrites, and leaves 'keep mine' alone")
    void toInstallHonoursChoice() {
        Set<String> exists = Set.of("swamp", "desert");
        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("swamp", json(SWAMP.withName("Marsh")));   // exists, chosen → written
        incoming.put("desert", json(DESERT));                   // exists, kept → not written
        incoming.put("copper", json(SWAMP.withName("copper"))); // missing → written
        incoming.put("broken", "{");                            // unreadable → skipped, not fatal
        Map<String, Stage> out = TemplateStages.toInstall(incoming, Set.of("swamp"), exists::contains);
        assertEquals(Set.of("swamp", "copper"), out.keySet());
        assertEquals("Marsh", out.get("swamp").name());
        assertEquals(Map.of(), TemplateStages.toInstall(null, Set.of(), exists::contains));
        assertEquals(Set.of("copper"), TemplateStages.toInstall(incoming, null, exists::contains).keySet(),
                "no overwrite set = keep every local stage");
    }

    @Test
    @DisplayName("parseJsonText refuses a blank id or non-object text rather than inventing a default stage")
    void parseRefusesGarbage() {
        assertNull(StageStore.parseJsonText("", json(SWAMP)));
        assertNull(StageStore.parseJsonText("swamp", ""));
        assertNull(StageStore.parseJsonText("swamp", "[1,2]"));
        assertNull(StageStore.parseJsonText("swamp", "{"));
        assertEquals(SWAMP, StageStore.parseJsonText("SWAMP", json(SWAMP)), "ids are lowercased");
    }
}
