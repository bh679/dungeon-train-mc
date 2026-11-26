package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMeta;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Frames menu row carries the frame's weight, spawn gate and Stage, as every other template row does. */
final class ChunkFrameMenuRowTest {

    @Test
    @DisplayName("Frames row carries weight, levels, bands and dates")
    void carriesWeightAndGate() {
        TrainPhase first = TrainPhase.values()[0];
        TemplateGate gate = new TemplateGate(3, 9, EnumSet.of(first), Set.of());
        ChunkFrameMeta meta = ChunkFrameMeta.DEFAULT.withWeight(7).withGate(gate);

        EditorTypeMenusPacket.Variant row = EditorTypeMenus.chunkFrameRow("stone_frame", meta, gate, false, false);

        assertEquals(7, row.weight());
        assertNotEquals(games.brennan.dungeontrain.net.EditorPlotLabelsPacket.NO_WEIGHT, row.weight());
        assertEquals(3, row.minLevel());
        assertEquals(9, row.maxLevel());
        assertEquals(TrainPhase.toMask(EnumSet.of(first)), row.phaseMask());
        assertEquals(PlotCategory.CHUNK_FRAMES, row.plotCategory());
        assertEquals(ChunkFrameEditor.MODEL_ID, row.modelId());
        assertEquals("stone_frame", row.modelName());
        assertTrue(row.stageIds().isEmpty(), "a frame with its own gate reads as Custom");
    }

    @Test
    @DisplayName("Stage-linked frame shows its Stage")
    void carriesStage() {
        ChunkFrameMeta meta = ChunkFrameMeta.DEFAULT.withStage("deep");
        EditorTypeMenusPacket.Variant row = EditorTypeMenus.chunkFrameRow(
            "stone_frame", meta, TemplateGate.DEFAULT, true, false);
        assertEquals(List.of("deep"), row.stageIds());
    }
}
