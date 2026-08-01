package games.brennan.dungeontrain.template;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins {@link StageResolver} against the <b>shipped</b> stage bands, because the answer decides which
 * pool a shared carriage joins: a wrong stage either benches a build forever or lets a stone-era room
 * surface in the deep dark.
 */
final class StageResolverTest {

    private static List<Stage> stages;

    @BeforeAll
    static void loadBundledStages() {
        InputStream in = StageResolverTest.class.getResourceAsStream("/data/dungeontrain/stages.json");
        assertNotNull(in, "bundled stages.json must ship on the classpath");
        JsonElement root;
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(r);
        } catch (Exception e) {
            throw new AssertionError("bundled stages.json failed to read: " + e, e);
        }
        List<Stage> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
            out.add(Stage.fromJson(e.getKey(), e.getValue().getAsJsonObject()));
        }
        assertFalse(out.isEmpty(), "expected the bundled stages to parse");
        stages = out;
    }

    private static String at(int level, TrainPhase phase) {
        return StageResolver.stageIdFor(level, phase, stages);
    }

    @Test
    @DisplayName("each overworld level band resolves to its own stage")
    void levelBandsMapToTheirStage() {
        assertEquals("stone", at(0, TrainPhase.OVERWORLD));
        assertEquals("stone", at(10, TrainPhase.OVERWORLD));
        assertEquals("desert", at(11, TrainPhase.OVERWORLD));
        assertEquals("desert", at(35, TrainPhase.OVERWORLD));
        assertEquals("copper", at(36, TrainPhase.OVERWORLD));
        assertEquals("quartz", at(51, TrainPhase.OVERWORLD));
        assertEquals("obsidian", at(71, TrainPhase.OVERWORLD));
        assertEquals("deepdark", at(87, TrainPhase.OVERWORLD));
        assertEquals("mud", at(121, TrainPhase.OVERWORLD));
        assertEquals("wood_oak", at(131, TrainPhase.OVERWORLD));
    }

    @Test
    @DisplayName("the END overlap prefers the narrow level band over the unbounded nether stage")
    void endOverlapPrefersTheSpecificStage() {
        // `nether` has no level bounds and lists END, so it is eligible everywhere in the End; a
        // first-eligible scan would return whichever the map yielded first. The narrower band wins.
        assertEquals("stone", at(5, TrainPhase.END));
        assertEquals("desert", at(20, TrainPhase.END));
        assertEquals("wood_oak", at(140, TrainPhase.END));
    }

    @Test
    @DisplayName("the NETHER phase resolves to nether at every level")
    void netherPhaseResolvesToNether() {
        assertEquals("nether", at(0, TrainPhase.NETHER));
        assertEquals("nether", at(60, TrainPhase.NETHER));
        assertEquals("nether", at(500, TrainPhase.NETHER));
    }

    @Test
    @DisplayName("a level above every band resolves to no stage (and so can never be pooled)")
    void aboveAllBandsResolvesToNull() {
        assertNull(at(5000, TrainPhase.OVERWORLD));
    }

    @Test
    @DisplayName("resolution is deterministic — repeated calls agree")
    void resolutionIsStable() {
        for (int i = 0; i < 5; i++) {
            assertEquals("copper", at(40, TrainPhase.OVERWORLD));
            assertEquals("nether", at(40, TrainPhase.NETHER));
        }
    }

    @Test
    @DisplayName("an empty or null stage set resolves to null rather than throwing")
    void emptyStageSetIsNull() {
        assertNull(StageResolver.stageIdFor(5, TrainPhase.OVERWORLD, List.of()));
        assertNull(StageResolver.stageIdFor(5, TrainPhase.OVERWORLD, null));
    }
}
