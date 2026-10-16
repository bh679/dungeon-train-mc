package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.item.VariantClipboardItem;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lock group that a variant option refers to keeps existing when no cell in the template carries
 * its lock-id any more: the reference carries a copy of the group's options
 * ({@link VariantState#groupStates}), and {@link VariantGroupResolver} answers from it.
 */
final class VariantGroupRefPersistenceTest {

    private static final BlockPos CELL_1 = new BlockPos(1, 1, 1);
    private static final BlockPos CELL_2 = new BlockPos(2, 1, 1);
    private static final BlockPos CELL_3 = new BlockPos(3, 1, 1);

    private static final Set<Block> GROUP_ONE_BLOCKS = Set.of(Blocks.STONE, Blocks.ANDESITE, Blocks.DIORITE);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static VariantState of(Block block) {
        return VariantState.of(block.defaultBlockState());
    }

    private static List<VariantState> groupOnePool() {
        return List.of(of(Blocks.STONE), of(Blocks.ANDESITE), of(Blocks.DIORITE));
    }

    /** A reference carrying {@code options} as its copy of group {@code group}. */
    private static VariantState refWithCopy(int group, List<VariantState> options) {
        return VariantState.ofGroupRef(group, options);
    }

    private static Block blockOf(VariantState s) {
        return s == null ? null : s.state().getBlock();
    }

    @Test
    @DisplayName("a group only a reference knows about is live and rolls like a real group")
    void referenceOnlyGroupIsLive() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), refWithCopy(1, groupOnePool())));
        sc.setLockId(CELL_2, 2);

        assertTrue(sc.groupRefs().graph().isLiveRef(2, 1), "a reference with a copy of its group must stay live");
        assertTrue(sc.groupRefs().lockIdsInUse().contains(1));

        int[] weights = VariantGroupRefs.weightsOf(groupOnePool());
        int deferred = 0;
        for (int seed = 0; seed < 16; seed++) {
            for (int idx = 0; idx < 16; idx++) {
                Block picked = blockOf(sc.resolve(CELL_2, seed, idx));
                assertNotEquals(Blocks.BEDROCK, picked);
                if (picked == Blocks.OAK_PLANKS) continue;
                deferred++;
                Block expected = blockOf(groupOnePool().get(
                    CarriageVariantBlocks.pickIndexFromLockGroup(1, seed, idx, weights)));
                assertEquals(expected, picked, "the copy must roll exactly as the group's own cells would");
            }
        }
        assertTrue(deferred > 0, "the reference never won a roll");
    }

    @Test
    @DisplayName("removing a group's last cell keeps it alive for the reference, with its latest options")
    void lastCellRemovedKeepsLatestOptions() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_1, groupOnePool());
        sc.setLockId(CELL_1, 1);
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), refWithCopy(1, groupOnePool())));
        // The group is edited after the reference copied it, then loses its only cell.
        sc.put(CELL_1, List.of(of(Blocks.GLASS), of(Blocks.SAND)));
        sc.remove(CELL_1);

        List<VariantState> options = sc.groupRefs().statesForLockId(1);
        assertNotNull(options, "group 1 vanished with its last cell");
        assertEquals(Blocks.GLASS, blockOf(options.get(0)), "the add-time copy won over the latest edit");
        assertEquals(2, options.size());

        // And the save writes the latest options into the reference.
        List<VariantState> saved = sc.groupRefs().refreshed(sc.statesAt(CELL_2), 0);
        assertEquals(Blocks.GLASS, blockOf(saved.get(1).groupStates().get(0)));
    }

    @Test
    @DisplayName("a cell carrying the lock-id beats the reference's copy")
    void cellBeatsCopy() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), refWithCopy(1, List.of(of(Blocks.GLASS), of(Blocks.SAND)))));
        sc.put(CELL_1, groupOnePool());
        sc.setLockId(CELL_1, 1);

        assertEquals(groupOnePool(), sc.groupRefs().statesForLockId(1));
        Set<Block> seen = new HashSet<>();
        for (int idx = 0; idx < 64; idx++) seen.add(blockOf(sc.resolve(CELL_2, 3L, idx)));
        assertFalse(seen.contains(Blocks.GLASS) || seen.contains(Blocks.SAND), "the stale copy was rolled");
        assertTrue(seen.stream().anyMatch(GROUP_ONE_BLOCKS::contains));
    }

    @Test
    @DisplayName("a group nobody refers to still disappears with its last cell")
    void unreferencedGroupDisappears() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_1, groupOnePool());
        sc.setLockId(CELL_1, 1);
        sc.remove(CELL_1);
        assertNull(sc.groupRefs().statesForLockId(1));
        assertFalse(sc.groupRefs().lockIdsInUse().contains(1));
        assertEquals(1, sc.nextFreeLockId(), "an id nothing uses should be handed out again");
    }

    @Test
    @DisplayName("a new lock never takes the id of a group kept alive by a reference")
    void nextFreeLockIdSkipsReferencedGroups() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), refWithCopy(1, groupOnePool())));
        assertEquals(2, sc.nextFreeLockId());
    }

    @Test
    @DisplayName("refreshed() nests copies but never the group a list sits in")
    void refreshedStopsAtOwnGroup() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_1, List.of(of(Blocks.STONE), of(Blocks.ANDESITE)));
        sc.setLockId(CELL_1, 1);
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), VariantState.ofGroupRef(1, Blocks.BEDROCK.defaultBlockState())));
        sc.setLockId(CELL_2, 2);
        sc.put(CELL_3, List.of(of(Blocks.GLASS), VariantState.ofGroupRef(2, Blocks.BEDROCK.defaultBlockState())));
        sc.setLockId(CELL_3, 3);

        List<VariantState> saved = sc.groupRefs().refreshed(sc.statesAt(CELL_3), 3);
        List<VariantState> groupTwo = saved.get(1).groupStates();
        assertEquals(2, groupTwo.size(), "group 2's copy missing");
        List<VariantState> groupOne = groupTwo.get(1).groupStates();
        assertEquals(Blocks.STONE, blockOf(groupOne.get(0)), "the nested reference did not carry group 1");

        List<VariantState> own = sc.groupRefs().refreshed(
            List.of(of(Blocks.GLASS), VariantState.ofGroupRef(3, Blocks.BEDROCK.defaultBlockState())), 3);
        assertTrue(own.get(1).groupStates().isEmpty(), "a list copied the group it belongs to");
    }

    @Test
    @DisplayName("the copy survives the sidecar JSON, nested copies included; a copy-free ref writes as before")
    void jsonRoundTrip() {
        VariantState inner = refWithCopy(1, groupOnePool());
        VariantState outer = refWithCopy(2, List.of(of(Blocks.OAK_PLANKS), inner));
        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendVariantJson(sb, outer);

        VariantState parsed = CarriageVariantBlocks.parseVariantElement(
            com.google.gson.JsonParser.parseString(sb.toString()),
            BuiltInRegistries.BLOCK.asLookup(), "test", CELL_1);
        assertNotNull(parsed);
        assertEquals(2, parsed.groupRef());
        assertEquals(2, parsed.groupStates().size());
        VariantState parsedInner = parsed.groupStates().get(1);
        assertEquals(1, parsedInner.groupRef());
        assertEquals(groupOnePool(), parsedInner.groupStates());

        StringBuilder bare = new StringBuilder();
        CarriageVariantBlocks.appendVariantJson(bare, VariantState.ofGroupRef(3, Blocks.STONE.defaultBlockState()));
        assertFalse(bare.toString().contains("groupStates"), "an empty copy must not be written: " + bare);
    }

    @Test
    @DisplayName("the copy survives the Variant Clipboard's NBT")
    void clipboardRoundTrip() {
        List<VariantState> cell = List.of(of(Blocks.OAK_PLANKS), refWithCopy(1, groupOnePool()));
        List<VariantState> decoded = VariantClipboardItem.decodeStates(VariantClipboardItem.encodeStates(cell, 4));
        assertEquals(2, decoded.size());
        assertEquals(1, decoded.get(1).groupRef());
        assertEquals(groupOnePool(), decoded.get(1).groupStates());
    }

    @Test
    @DisplayName("only a reference carries a copy")
    void nonReferenceHasNoCopy() {
        VariantState plain = of(Blocks.STONE).withGroupStates(groupOnePool());
        assertTrue(plain.groupStates().isEmpty());
        assertTrue(refWithCopy(1, groupOnePool()).withGroupRef(0).groupStates().isEmpty(),
            "clearing the reference must drop the copy");
    }
}
