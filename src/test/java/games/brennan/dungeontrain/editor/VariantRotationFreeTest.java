package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lock group's Rot Lock / Rot Free setting: stored on each grouped cell as
 * {@code "rotation": "free"}, omitted at the locked default, and read by every placer through
 * {@link VariantDoc#rotationLockIdAt} so a free group's cells face their own ways while still
 * sharing one block (and one open/closed roll).
 */
final class VariantRotationFreeTest {

    private static final BlockPos CELL = new BlockPos(1, 2, 3);
    private static final Vec3i BOX = new Vec3i(8, 8, 8);
    private static final long SEED = 0x5EEDL;
    private static final int INDEX = 4;
    private static final int GROUP = 3;
    private static final String FREE_FIELD = "\"rotation\": \"free\"";

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<VariantState> states() {
        return List.of(VariantState.of(Blocks.STONE.defaultBlockState()),
            VariantState.of(Blocks.COBBLESTONE.defaultBlockState()));
    }

    /** A stair candidate that may face any of the four horizontal directions. */
    private static VariantState anyWayStair() {
        int mask = VariantRotation.maskOf(EnumSet.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST));
        return VariantState.of(Blocks.OAK_STAIRS.defaultBlockState()).withRotation(VariantRotation.options(mask));
    }

    private static VariantDoc groupedDoc() {
        VariantDoc doc = CarriageVariantBlocks.empty();
        doc.put(CELL, states());
        doc.setLockId(CELL, GROUP);
        return doc;
    }

    @Test
    @DisplayName("a free group cell round-trips as \"rotation\": \"free\" and rolls rotation by position")
    void freeRoundTrips() {
        VariantDoc doc = groupedDoc();
        doc.setRotationFree(CELL, true);
        String json = doc.toJsonText();
        assertTrue(json.contains(FREE_FIELD), json);

        VariantDoc reloaded = VariantDoc.fromJsonText(json, MirrorDefaults.NONE, "carriage", BOX);
        assertTrue(reloaded.rotationFreeAt(CELL));
        assertEquals(GROUP, reloaded.lockIdAt(CELL), "the block roll stays on the group");
        assertEquals(0, reloaded.rotationLockIdAt(CELL), "the rotation roll leaves the group");
        assertTrue(VariantDoc.copyOf(reloaded).rotationFreeAt(CELL), "a copy keeps the setting");
    }

    @Test
    @DisplayName("Rot Lock is the default: nothing written, and the rotation rolls on the group id")
    void lockedIsDefaultAndOmitted() {
        VariantDoc doc = groupedDoc();
        assertFalse(doc.rotationFreeAt(CELL));
        assertEquals(GROUP, doc.rotationLockIdAt(CELL));
        assertFalse(doc.toJsonText().contains(FREE_FIELD));

        doc.setRotationFree(CELL, true);
        doc.setRotationFree(CELL, false);
        assertFalse(doc.toJsonText().contains(FREE_FIELD), "locking again writes the default back");
    }

    @Test
    @DisplayName("leaving the lock group drops the setting, and an ungrouped cell never writes it")
    void ungroupedCellCarriesNothing() {
        VariantDoc doc = groupedDoc();
        doc.setRotationFree(CELL, true);
        doc.setLockId(CELL, 0);
        assertFalse(doc.rotationFreeAt(CELL));
        assertFalse(doc.toJsonText().contains(FREE_FIELD));

        String handEdited = """
            {
              "schemaVersion": 10,
              "variants": {
                "1,2,3": { "rotation": "free", "states": ["minecraft:stone", "minecraft:cobblestone"] }
              }
            }
            """;
        VariantDoc reloaded = VariantDoc.fromJsonText(handEdited, MirrorDefaults.NONE, "carriage", BOX);
        assertFalse(reloaded.rotationFreeAt(CELL), "no group, nothing to free");
    }

    @Test
    @DisplayName("Rot Lock: every cell of a group faces the same way")
    void lockedGroupSharesFacing() {
        VariantState stair = anyWayStair();
        Set<Direction> seen = new HashSet<>();
        for (int x = 0; x < 24; x++) {
            BlockPos pos = new BlockPos(x, 1, x % 5);
            BlockState placed = RotationApplier.apply(stair.state(), stair, pos, SEED, INDEX, GROUP, GROUP);
            seen.add(placed.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        assertEquals(1, seen.size(), "locked group: " + seen);
    }

    @Test
    @DisplayName("Rot Free: a group's cells roll their own facing, as ungrouped cells do")
    void freeGroupVariesFacing() {
        VariantState stair = anyWayStair();
        Set<Direction> seen = new HashSet<>();
        for (int x = 0; x < 24; x++) {
            BlockPos pos = new BlockPos(x, 1, x % 5);
            BlockState free = RotationApplier.apply(stair.state(), stair, pos, SEED, INDEX, GROUP, 0);
            BlockState ungrouped = RotationApplier.apply(stair.state(), stair, pos, SEED, INDEX, 0);
            assertEquals(ungrouped, free, "a free cell rolls exactly as an ungrouped one at " + pos);
            seen.add(free.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        assertTrue(seen.size() > 1, "free group should face more than one way: " + seen);
    }

    @Test
    @DisplayName("a default-rotation row keeps its placed facing; Rot Free varies only its upside-down flip")
    void defaultRowVariesFlipOnly() {
        VariantState stair = VariantState.of(Blocks.OAK_STAIRS.defaultBlockState());
        Set<Object> lockedHalves = new HashSet<>();
        Set<Object> freeHalves = new HashSet<>();
        Set<Direction> freeFacings = new HashSet<>();
        for (int x = 0; x < 24; x++) {
            BlockPos pos = new BlockPos(x, 1, x % 5);
            lockedHalves.add(RotationApplier.apply(stair.state(), stair, pos, SEED, INDEX, GROUP, GROUP)
                .getValue(BlockStateProperties.HALF));
            BlockState free = RotationApplier.apply(stair.state(), stair, pos, SEED, INDEX, GROUP, 0);
            freeHalves.add(free.getValue(BlockStateProperties.HALF));
            freeFacings.add(free.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        assertEquals(1, lockedHalves.size(), "locked group flips as one: " + lockedHalves);
        assertTrue(freeHalves.size() > 1, "free group flips per cell: " + freeHalves);
        assertEquals(Set.of(Direction.NORTH), freeFacings, "default rotation never turns the block");
    }

    @Test
    @DisplayName("Rot Free frees only the orientation: the open/closed roll stays shared by the group")
    void freeKeepsToggleShared() {
        int mask = VariantRotation.maskOf(EnumSet.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST));
        VariantState trapdoor = VariantState.of(Blocks.OAK_TRAPDOOR.defaultBlockState())
            .withRotation(VariantRotation.options(mask))
            .withActive(VariantActive.random());
        Set<Boolean> open = new HashSet<>();
        for (int x = 0; x < 24; x++) {
            BlockPos pos = new BlockPos(x, 1, x % 5);
            BlockState placed = RotationApplier.apply(trapdoor.state(), trapdoor, pos, SEED, INDEX, GROUP, 0);
            open.add(placed.getValue(BlockStateProperties.OPEN));
        }
        assertEquals(1, open.size(), "open/closed must still roll once for the group: " + open);
    }
}
