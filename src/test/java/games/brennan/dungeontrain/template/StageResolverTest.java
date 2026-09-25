package games.brennan.dungeontrain.template;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.worldgen.LapBand;
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

    private static String at(int level, LapBand phase) {
        return StageResolver.stageIdFor(level, phase, stages);
    }

    @Test
    @DisplayName("each overworld level band resolves to its own stage")
    void levelBandsMapToTheirStage() {
        assertEquals("stone", at(0, LapBand.V_OVERWORLD_1));
        assertEquals("stone", at(10, LapBand.V_OVERWORLD_1));
        assertEquals("desert", at(11, LapBand.V_OVERWORLD_1));
        assertEquals("desert", at(35, LapBand.V_OVERWORLD_1));
        assertEquals("copper", at(36, LapBand.V_OVERWORLD_1));
        assertEquals("quartz", at(51, LapBand.V_OVERWORLD_1));
        assertEquals("obsidian", at(71, LapBand.V_OVERWORLD_1));
        assertEquals("deepdark", at(87, LapBand.V_OVERWORLD_1));
        assertEquals("mud", at(121, LapBand.V_OVERWORLD_1));
        assertEquals("wood_oak", at(131, LapBand.V_OVERWORLD_1));
        // The wood stages run on from oak in the author's order and lengths, up to the open-ended top.
        assertEquals("wood_oak", at(160, LapBand.V_OVERWORLD_1));
        assertEquals("birch", at(161, LapBand.V_OVERWORLD_1));
        assertEquals("acacia", at(176, LapBand.V_OVERWORLD_1));
        assertEquals("jungle", at(206, LapBand.V_OVERWORLD_1));
        assertEquals("mangrove", at(226, LapBand.V_OVERWORLD_1));
        assertEquals("cherry", at(266, LapBand.V_OVERWORLD_1));
        assertEquals("crimson", at(286, LapBand.V_OVERWORLD_1));
        assertEquals("warped", at(321, LapBand.V_OVERWORLD_1));
        assertEquals("bamboo", at(356, LapBand.V_OVERWORLD_1));
        assertEquals("darkwood", at(376, LapBand.V_OVERWORLD_1));
        assertEquals("bamboo_mosaic", at(416, LapBand.V_OVERWORLD_1));
        assertEquals("spruce", at(436, LapBand.V_OVERWORLD_1));
    }

    /**
     * The End band is deliberately not an overworld band with void under it: the 8 overworld stages no
     * longer list END, leaving the unbounded {@code nether} stage as the only one eligible there. So a
     * shared slot in the End draws Nether-styled content at every level rather than tracking the
     * level-appropriate overworld tier.
     */
    @Test
    @DisplayName("the END phase resolves to nether at every level — no overworld tier reaches the End")
    void endPhaseResolvesToNether() {
        assertEquals("nether", at(5, LapBand.V_END));
        assertEquals("nether", at(20, LapBand.V_END));
        assertEquals("nether", at(140, LapBand.V_END));
    }

    /**
     * The resolver's tie-break, guarded on a synthetic set because the shipped bands no longer overlap
     * anywhere: an unbounded stage and a narrow level band that both list a phase must resolve to the
     * narrow one, not to whichever the iteration order yielded first. Nothing in {@code stages.json}
     * exercises this today, but the rule is what keeps a future unbounded stage from swallowing a band.
     */
    @Test
    @DisplayName("a narrow level band beats an unbounded stage that lists the same phase")
    void narrowBandBeatsUnboundedStage() {
        List<Stage> synthetic = List.of(
                Stage.fromJson("catch_all", JsonParser.parseString(
                        "{\"name\":\"Catch All\",\"phases\":[\"END\"]}")),
                Stage.fromJson("narrow", JsonParser.parseString(
                        "{\"name\":\"Narrow\",\"minLevel\":10,\"maxLevel\":20,\"phases\":[\"END\"]}")));
        assertEquals("narrow", StageResolver.stageIdFor(15, LapBand.V_END, synthetic));
        // Outside the narrow band the unbounded stage is the only eligible one.
        assertEquals("catch_all", StageResolver.stageIdFor(50, LapBand.V_END, synthetic));
    }

    @Test
    @DisplayName("the NETHER phase resolves to nether at every level")
    void netherPhaseResolvesToNether() {
        assertEquals("nether", at(0, LapBand.V_NETHER));
        assertEquals("nether", at(60, LapBand.V_NETHER));
        assertEquals("nether", at(500, LapBand.V_NETHER));
    }

    /**
     * The top overworld band is open-ended on purpose. It used to stop at level 200, so a long run
     * eventually rode into levels no stage covered — every shared slot there was skipped as
     * {@code NO_STAGE}, silently, for the rest of the run. Whichever wood is last in the sequence
     * holds that open end — {@code spruce} today — so adding a wood means moving this, not capping it.
     */
    @Test
    @DisplayName("a level above every named band still resolves — the top band runs open-ended")
    void aboveAllBandsResolvesToTheTopStage() {
        assertEquals("spruce", at(436, LapBand.V_OVERWORLD_1));
        assertEquals("spruce", at(5000, LapBand.V_OVERWORLD_1));
        // The top band runs open-ended in every phase it lists — but not END, which it no longer lists.
        assertEquals("spruce", at(5000, LapBand.C_CHUNCKS));
    }

    /** The chuncks band is on by default; a slot there must belong to its level's stage, not to nothing. */
    @Test
    @DisplayName("the CHUNCKS phase resolves to the level's stage")
    void chuncksPhaseResolvesToTheLevelBand() {
        assertEquals("stone", at(5, LapBand.C_CHUNCKS));
        assertEquals("copper", at(40, LapBand.C_CHUNCKS));
        assertEquals("crimson", at(300, LapBand.C_CHUNCKS));
    }

    /** A genuinely uncovered {@code (level, phase)} still resolves to null rather than guessing. */
    @Test
    @DisplayName("an uncovered phase resolves to null")
    void uncoveredPhaseIsNull() {
        List<Stage> netherOnly = stages.stream().filter(s -> s.id().equals("nether")).toList();
        assertNull(StageResolver.stageIdFor(5, LapBand.V_OVERWORLD_1, netherOnly));
    }

    @Test
    @DisplayName("resolution is deterministic — repeated calls agree")
    void resolutionIsStable() {
        for (int i = 0; i < 5; i++) {
            assertEquals("copper", at(40, LapBand.V_OVERWORLD_1));
            assertEquals("nether", at(40, LapBand.V_NETHER));
        }
    }

    @Test
    @DisplayName("an empty or null stage set resolves to null rather than throwing")
    void emptyStageSetIsNull() {
        assertNull(StageResolver.stageIdFor(5, LapBand.V_OVERWORLD_1, List.of()));
        assertNull(StageResolver.stageIdFor(5, LapBand.V_OVERWORLD_1, null));
    }
}
