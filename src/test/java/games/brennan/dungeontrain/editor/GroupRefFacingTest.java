package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.item.VariantClipboardItem;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lock-group reference that pins a facing ({@link GroupRefFacing}): one Rot Free group added to a
 * cell once per facing. The value the group resolves to must come out at the reference's facing,
 * a block that cannot take it must come out untouched, and a plain reference must behave exactly
 * as before.
 */
final class GroupRefFacingTest {

    private static final BlockPos CELL_1 = new BlockPos(1, 1, 1);
    private static final BlockPos CELL_1B = new BlockPos(1, 1, 2);
    private static final BlockPos CELL_2 = new BlockPos(2, 1, 1);

    private static final VariantRotation NORTH = VariantRotation.lock(Direction.NORTH);
    private static final VariantRotation EAST = VariantRotation.lock(Direction.EAST);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static VariantState of(Block block) {
        return VariantState.of(block.defaultBlockState());
    }

    private static List<VariantState> stairsPool() {
        return List.of(of(Blocks.STONE_STAIRS), of(Blocks.OAK_STAIRS));
    }

    /** Group 1 = Rot Free stairs at two cells; CELL_2 = glass, group 1 north, group 1 east. */
    private static VariantDoc twoFacings(List<VariantState> groupPool) {
        VariantDoc sc = CarriageVariantBlocks.empty();
        sc.put(CELL_1, groupPool);
        sc.put(CELL_1B, groupPool);
        sc.setLockId(CELL_1, 1);
        sc.setLockId(CELL_1B, 1);
        sc.setRotationFree(CELL_1, true);
        sc.setRotationFree(CELL_1B, true);
        sc.put(CELL_2, List.of(of(Blocks.GLASS),
            VariantState.ofGroupRef(1, groupPool, NORTH),
            VariantState.ofGroupRef(1, groupPool, EAST)));
        return sc;
    }

    @Test
    @DisplayName("each reference places the group's block at its own pinned facing")
    void eachReferencePinsItsFacing() {
        VariantDoc sc = twoFacings(stairsPool());
        Set<VariantRotation> seen = new java.util.HashSet<>();
        for (int seed = 0; seed < 32; seed++) {
            for (int idx = 0; idx < 32; idx++) {
                VariantState picked = sc.resolve(CELL_2, seed, idx);
                assertNotNull(picked);
                if (picked.state().is(Blocks.GLASS)) continue;
                assertEquals(sc.resolve(CELL_1, seed, idx).state().getBlock(), picked.state().getBlock(),
                    "the reference placed a different block than its group did");
                assertTrue(picked.rotation().equals(NORTH) || picked.rotation().equals(EAST),
                    "the reference's facing was not applied: " + picked.rotation());
                assertFalse(picked.isGroupRef());
                seen.add(picked.rotation());
                assertEquals(picked, sc.resolve(CELL_2, seed, idx), "the pick is not deterministic");
            }
        }
        assertEquals(Set.of(NORTH, EAST), seen, "both facings should win some rolls");
        // The group's own cells are untouched by the pin.
        assertTrue(sc.resolve(CELL_1, 0, 0).rotation().isDefault());
    }

    @Test
    @DisplayName("a block that cannot take the facing is placed as it is")
    void nonRotatableTerminalUntouched() {
        VariantState ref = VariantState.ofGroupRef(1, stairsPool(), EAST);
        VariantState planks = of(Blocks.OAK_PLANKS);
        assertSame(planks, GroupRefFacing.apply(ref, planks));
        VariantState log = of(Blocks.OAK_LOG);
        assertEquals(EAST, GroupRefFacing.apply(ref, log).rotation(), "an axis block takes an x facing");
    }

    @Test
    @DisplayName("a plain reference still returns the group's value whole")
    void plainReferenceUnchanged() {
        VariantState plain = VariantState.ofGroupRef(1, stairsPool());
        assertTrue(plain.rotation().isDefault());
        assertEquals(plain, VariantState.ofGroupRef(1, stairsPool(), VariantRotation.NONE));
        VariantState stairs = of(Blocks.STONE_STAIRS).withRotation(VariantRotation.lock(Direction.SOUTH));
        assertSame(stairs, GroupRefFacing.apply(plain, stairs));
    }

    @Test
    @DisplayName("the row's icon is the group's block turned to the facing")
    void placeholderIsTurned() {
        VariantState ref = VariantState.ofGroupRef(1,
            List.of(of(Blocks.OAK_PLANKS), of(Blocks.STONE_STAIRS)), EAST);
        assertTrue(ref.state().is(Blocks.STONE_STAIRS), "the icon should be the first rotatable option");
        assertEquals(Direction.EAST, ref.state().getValue(StairBlock.FACING));
        assertEquals("east", GroupRefFacing.facingLabel(ref));
    }

    @Test
    @DisplayName("same group, different facing is a different reference; same facing is a duplicate")
    void sameRefComparesFacing() {
        VariantState north = VariantState.ofGroupRef(1, stairsPool(), NORTH);
        VariantState east = VariantState.ofGroupRef(1, stairsPool(), EAST);
        assertFalse(GroupRefFacing.sameRef(north, east));
        assertTrue(GroupRefFacing.sameRef(east, VariantState.ofGroupRef(1, stairsPool(), EAST)));
        assertFalse(GroupRefFacing.sameRef(east, VariantState.ofGroupRef(2, stairsPool(), EAST)));
    }

    @Test
    @DisplayName("the pinned facing survives the sidecar JSON and the clipboard")
    void persistence() {
        VariantState east = VariantState.ofGroupRef(1, stairsPool(), EAST);
        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendVariantJson(sb, east);
        VariantState parsed = CarriageVariantBlocks.parseVariantElement(
            com.google.gson.JsonParser.parseString(sb.toString()),
            BuiltInRegistries.BLOCK.asLookup(), "test", CELL_2);
        assertNotNull(parsed);
        assertEquals(EAST, parsed.rotation());
        assertEquals(1, parsed.groupRef());
        assertEquals(east.state(), parsed.state());

        List<VariantState> decoded = VariantClipboardItem.decodeStates(
            VariantClipboardItem.encodeStates(List.of(of(Blocks.GLASS), east), 0));
        assertEquals(EAST, decoded.get(1).rotation());

        CompoundTag tag = VariantClipboardItem.withRefRotation(
            VariantClipboardItem.encodeStates(stairsPool(), 1), EAST);
        assertEquals(EAST, VariantClipboardItem.decodeRefRotation(tag));
        assertTrue(VariantClipboardItem.decodeRefRotation(
            VariantClipboardItem.encodeStates(stairsPool(), 1)).isDefault(), "no facing unless recorded");
    }

    @Test
    @DisplayName("mirroring a template turns the pinned facing with it")
    void mirrorTurnsFacing() {
        VariantState east = VariantState.ofGroupRef(1, stairsPool(), EAST);
        VariantState mirrored = EditorMirror.reflectVariant(east, true, false, false);
        assertEquals(VariantRotation.lock(Direction.WEST), mirrored.rotation());
        assertEquals(1, mirrored.groupRef());
    }
}
