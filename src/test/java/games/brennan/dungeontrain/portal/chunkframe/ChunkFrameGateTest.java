package games.brennan.dungeontrain.portal.chunkframe;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A chunk frame's spawn gate: how it is stored, and how it narrows which frame a pair can pick. */
class ChunkFrameGateTest {

    @Test
    @DisplayName("gate and stage round-trip through frame.json; an old file reads as ungated and unlinked")
    void json() {
        ChunkFrameMeta meta = ChunkFrameMeta.DEFAULT
            .withGate(new TemplateGate(5, 12, EnumSet.of(TrainPhase.OVERWORLD, TrainPhase.NETHER), Set.of("christmas")))
            .withStage("Late")
            .withDimension(ChunkFrameDimension.VOID);
        ChunkFrameMeta back = ChunkFrameMeta.fromJson(meta.toJson());
        assertEquals(meta, back);
        assertEquals("late", back.stageId());

        JsonObject old = new JsonObject();
        old.addProperty("weight", 3);
        ChunkFrameMeta legacy = ChunkFrameMeta.fromJson(old);
        assertTrue(legacy.gate().isDefault());
        assertNull(legacy.stageId());
        JsonObject plain = ChunkFrameMeta.DEFAULT.toJson();
        assertFalse(plain.has("minLevel") || plain.has("maxLevel") || plain.has("phases") || plain.has("stage"),
            "an ungated frame writes no gate fields");
    }

    @Test
    @DisplayName("gate edits keep everything else; a max never falls under the min")
    void edits() {
        ChunkFrameMeta meta = ChunkFrameMeta.DEFAULT.withWeight(4).withDimension(ChunkFrameDimension.WORLD_BORDER);
        ChunkFrameMeta gated = meta.withMinLevel(6).withMaxLevel(3).withPhase(TrainPhase.NETHER, false);
        assertEquals(4, gated.weight());
        assertEquals(ChunkFrameDimension.WORLD_BORDER, gated.dimension());
        assertEquals(6, gated.gate().minLevel());
        assertEquals(6, gated.gate().maxLevel());
        assertFalse(gated.gate().phases().contains(TrainPhase.NETHER));
        assertEquals(TemplateGate.ALL, gated.withMaxLevel(TemplateGate.ALL).gate().maxLevel());
        assertEquals(gated.gate(), gated.withWeight(9).withAllRooms().withStage("x").gate());
    }

    private static final String ROOM = "chunk_dimension";

    private static String pick(Map<String, ChunkFrameMeta> frames, long seed, GateContext ctx) {
        return ChunkFramePlacer.pickAmong(List.copyOf(frames.keySet()), frames::get, ROOM, seed, ctx);
    }

    @Test
    @DisplayName("a frame gated out of the pair's level or band is never picked; no context means no gating")
    void gatedPick() {
        Map<String, ChunkFrameMeta> frames = new java.util.LinkedHashMap<>();
        frames.put("early", ChunkFrameMeta.DEFAULT.withMaxLevel(4));
        frames.put("late", ChunkFrameMeta.DEFAULT.withMinLevel(10));
        frames.put("nether_only", ChunkFrameMeta.DEFAULT
            .withGate(new TemplateGate(0, TemplateGate.ALL, EnumSet.of(TrainPhase.NETHER))));

        java.util.Set<String> lowOverworld = new java.util.HashSet<>();
        java.util.Set<String> highNether = new java.util.HashSet<>();
        java.util.Set<String> ungated = new java.util.HashSet<>();
        for (long seed = 0; seed < 400; seed++) {
            lowOverworld.add(pick(frames, seed, new GateContext(2, TrainPhase.OVERWORLD)));
            highNether.add(pick(frames, seed, new GateContext(15, TrainPhase.NETHER)));
            ungated.add(pick(frames, seed, null));
        }
        assertEquals(Set.of("early"), lowOverworld);
        assertEquals(Set.of("late", "nether_only"), highNether);
        assertEquals(frames.keySet(), ungated);
    }

    @Test
    @DisplayName("when no frame's gate admits the pair, there is no frame")
    void nothingAdmitted() {
        Map<String, ChunkFrameMeta> frames = Map.of("late", ChunkFrameMeta.DEFAULT.withMinLevel(50));
        assertNull(pick(frames, 7, new GateContext(1, TrainPhase.OVERWORLD)));
    }

    @Test
    @DisplayName("the pick is deterministic for a seed, and frames for other rooms are never candidates")
    void deterministicAndRoomBound() {
        Map<String, ChunkFrameMeta> frames = new java.util.LinkedHashMap<>();
        frames.put("a", ChunkFrameMeta.DEFAULT);
        frames.put("b", ChunkFrameMeta.DEFAULT.withNoRooms());
        for (long seed = 0; seed < 50; seed++) {
            assertEquals("a", pick(frames, seed, new GateContext(3, TrainPhase.OVERWORLD)));
            assertEquals(pick(frames, seed, null), pick(frames, seed, null));
        }
    }
}
