package games.brennan.dungeontrain.compat;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the bookkeeping behind {@link EffortlessBuildingMirror}: writes outside an action are
 * ignored, cells are deduplicated in write order, and a flush closes the record. The mirror
 * maths itself is covered by {@code EditorMirrorTest}.
 */
class EffortlessBuildingMirrorTest {

    @Test
    void recordWithNoActionOpenIsIgnored() {
        UUID id = UUID.randomUUID();
        EffortlessBuildingMirror.take(id);
        EffortlessBuildingMirror.record(new BlockPos(1, 2, 3));
        assertTrue(EffortlessBuildingMirror.take(id).isEmpty());
    }

    @Test
    void recordsAreDedupedInWriteOrder() {
        UUID id = UUID.randomUUID();
        EffortlessBuildingMirror.begin(id);
        EffortlessBuildingMirror.record(new BlockPos(5, 0, 0));
        EffortlessBuildingMirror.record(new BlockPos(1, 0, 0));
        EffortlessBuildingMirror.record(new BlockPos(5, 0, 0));
        Set<BlockPos> cells = EffortlessBuildingMirror.take(id);
        assertEquals(List.of(new BlockPos(5, 0, 0), new BlockPos(1, 0, 0)), List.copyOf(cells));
    }

    @Test
    void takeClosesTheRecord() {
        UUID id = UUID.randomUUID();
        EffortlessBuildingMirror.begin(id);
        EffortlessBuildingMirror.record(BlockPos.ZERO);
        EffortlessBuildingMirror.take(id);
        EffortlessBuildingMirror.record(new BlockPos(9, 9, 9));
        assertTrue(EffortlessBuildingMirror.take(id).isEmpty());
    }

    @Test
    void beginDropsAStaleRecord() {
        UUID id = UUID.randomUUID();
        EffortlessBuildingMirror.begin(id);
        EffortlessBuildingMirror.record(BlockPos.ZERO);
        EffortlessBuildingMirror.begin(id);
        assertTrue(EffortlessBuildingMirror.take(id).isEmpty());
    }
}
