package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@link VariantConditionCheck} and the retarget rule on the shape of the creekingroom
 * columns: lock cells at y = 0, 2, 4 and conditional cells between them gating on that group.
 */
final class VariantConditionCheckTest {

    private static final ResourceLocation LOG = ResourceLocation.withDefaultNamespace("pale_oak_log");
    private static final ResourceLocation AIR = ResourceLocation.withDefaultNamespace("air");

    private static VariantConditionCheck.Candidate plain(ResourceLocation block) {
        return new VariantConditionCheck.Candidate(block, VariantCondition.NONE);
    }

    private static VariantConditionCheck.Candidate ifGroup(ResourceLocation block, int group, ResourceLocation value) {
        return new VariantConditionCheck.Candidate(block, new VariantCondition(0, group, List.of(value)));
    }

    /** A column at (x, z): lock group {@code lock} at y 0/2/4, cells at y 1/3 gating on {@code gate}. */
    private static void column(Map<BlockPos, VariantConditionCheck.Cell> cells, int x, int z, int lock, int gate) {
        for (int y : new int[] {0, 2, 4}) {
            cells.put(new BlockPos(x, y, z), new VariantConditionCheck.Cell(lock, List.of(plain(LOG), plain(AIR))));
        }
        for (int y : new int[] {1, 3}) {
            cells.put(new BlockPos(x, y, z), new VariantConditionCheck.Cell(0,
                List.of(ifGroup(LOG, gate, LOG), ifGroup(AIR, gate, AIR))));
        }
    }

    @Test
    void wellFormedColumnsAreClean() {
        Map<BlockPos, VariantConditionCheck.Cell> cells = new LinkedHashMap<>();
        column(cells, 0, 0, 5, 5);
        column(cells, 6, 4, 3, 3);
        assertTrue(VariantConditionCheck.check(cells).isEmpty());
    }

    @Test
    void columnReadingAnotherGroupIsDetached() {
        Map<BlockPos, VariantConditionCheck.Cell> cells = new LinkedHashMap<>();
        column(cells, 0, 0, 5, 5);
        column(cells, 6, 4, 3, 5);
        List<VariantConditionCheck.Problem> problems = VariantConditionCheck.check(cells);
        assertEquals(2, problems.size());
        for (VariantConditionCheck.Problem p : problems) {
            assertEquals(VariantConditionCheck.Kind.DETACHED_GROUP, p.kind());
            assertEquals(5, p.group());
            assertEquals(3, p.otherGroup());
        }
    }

    @Test
    void conditionOnAnEmptyGroupIsMissing() {
        Map<BlockPos, VariantConditionCheck.Cell> cells = new LinkedHashMap<>();
        cells.put(BlockPos.ZERO, new VariantConditionCheck.Cell(0, List.of(ifGroup(LOG, 9, LOG), plain(AIR))));
        List<VariantConditionCheck.Problem> problems = VariantConditionCheck.check(cells);
        assertEquals(1, problems.size());
        assertEquals(VariantConditionCheck.Kind.MISSING_GROUP, problems.get(0).kind());
    }

    @Test
    void plainCellInAGatedColumnIsUngated() {
        Map<BlockPos, VariantConditionCheck.Cell> cells = new LinkedHashMap<>();
        column(cells, 0, 4, 4, 4);
        cells.put(new BlockPos(0, 3, 4), new VariantConditionCheck.Cell(0, List.of(plain(LOG), plain(AIR))));
        List<VariantConditionCheck.Problem> problems = VariantConditionCheck.check(cells);
        assertEquals(1, problems.size());
        assertEquals(VariantConditionCheck.Kind.UNGATED, problems.get(0).kind());
        assertEquals(new BlockPos(0, 3, 4), problems.get(0).pos());
    }

    @Test
    void twoGroupsGatingOnEachOtherLoop() {
        Map<BlockPos, VariantConditionCheck.Cell> cells = new LinkedHashMap<>();
        cells.put(new BlockPos(0, 0, 0), new VariantConditionCheck.Cell(1, List.of(ifGroup(LOG, 2, LOG), plain(AIR))));
        cells.put(new BlockPos(5, 0, 0), new VariantConditionCheck.Cell(2, List.of(ifGroup(LOG, 1, LOG), plain(AIR))));
        List<VariantConditionCheck.Problem> problems = VariantConditionCheck.check(cells);
        assertEquals(1, problems.stream().filter(p -> p.kind() == VariantConditionCheck.Kind.LOOP).count());
    }

    @Test
    void touchingSidesDependOnNeighbourCells() {
        Map<BlockPos, VariantConditionCheck.Cell> cells = new LinkedHashMap<>();
        int up = VariantCondition.bit(Direction.UP);
        int down = VariantCondition.bit(Direction.DOWN);
        cells.put(new BlockPos(0, 0, 0), new VariantConditionCheck.Cell(0, List.of(
            new VariantConditionCheck.Candidate(LOG, new VariantCondition(up, 0, List.of(LOG))), plain(AIR))));
        cells.put(new BlockPos(0, 1, 0), new VariantConditionCheck.Cell(0, List.of(
            new VariantConditionCheck.Candidate(LOG, new VariantCondition(down, 0, List.of(LOG))), plain(AIR))));
        assertEquals(VariantConditionCheck.Kind.LOOP, VariantConditionCheck.check(cells).get(0).kind());
    }

    @Test
    void retargetFollowsTheOnlyTouchedGroup() {
        VariantConditionCheck.Cell cell = new VariantConditionCheck.Cell(0,
            List.of(ifGroup(LOG, 5, LOG), ifGroup(AIR, 5, AIR)));
        assertEquals(3, VariantConditionCheck.retargetFor(cell, Set.of(3)));
        assertEquals(0, VariantConditionCheck.retargetFor(cell, Set.of(3, 5)), "still touches its own group");
        assertEquals(0, VariantConditionCheck.retargetFor(cell, Set.of(3, 4)), "ambiguous");
        assertEquals(0, VariantConditionCheck.retargetFor(cell, Set.of()), "touches nothing");
    }
}
