package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The append rule shared by single right-click authoring and the Effortless Building bulk
 * path: base-block seed on first edit, mob-on-air sentinel seed, and the soft cap.
 */
final class VariantAppendTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState COBBLE = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    @Test
    @DisplayName("first add seeds [base, added]")
    void firstAddSeedsWithBase() {
        VariantAppend.Result r = VariantAppend.append(null, VariantState.of(STONE), VariantState.of(COBBLE), STONE);
        assertTrue(r.accepted());
        assertEquals(2, r.pool().size());
        assertEquals(Blocks.STONE, r.pool().get(0).state().getBlock());
        assertEquals(Blocks.COBBLESTONE, r.pool().get(1).state().getBlock());
    }

    @Test
    @DisplayName("block added to an air cell is rejected with the base-block message")
    void airCellRejectsBlock() {
        VariantAppend.Result r = VariantAppend.append(null, null, VariantState.of(COBBLE), AIR);
        assertFalse(r.accepted());
        assertNotNull(r.rejectMessage());
        assertTrue(r.rejectMessage().contains("base block"));
    }

    @Test
    @DisplayName("mob added to an air cell seeds with the empty-space sentinel")
    void airCellSeedsMobWithSentinel() {
        VariantState mob = VariantState.ofMob(ResourceLocation.withDefaultNamespace("zombie"), null, 1, VariantRotation.NONE);
        VariantAppend.Result r = VariantAppend.append(null, null, mob, AIR);
        assertTrue(r.accepted());
        assertEquals(2, r.pool().size());
        assertTrue(CarriageVariantBlocks.isEmptyPlaceholder(r.pool().get(0).state()));
        assertTrue(r.pool().get(1).isMob());
    }

    @Test
    @DisplayName("existing pool grows by one; duplicates allowed")
    void existingPoolAppends() {
        List<VariantState> existing = List.of(VariantState.of(STONE), VariantState.of(COBBLE));
        VariantAppend.Result r = VariantAppend.append(existing, VariantState.of(STONE), VariantState.of(COBBLE), STONE);
        assertTrue(r.accepted());
        assertEquals(3, r.pool().size());
    }

    @Test
    @DisplayName("soft cap refuses a 17th entry")
    void capRefuses() {
        List<VariantState> full = new ArrayList<>();
        for (int i = 0; i < VariantAppend.MAX_VARIANTS_PER_POSITION; i++) full.add(VariantState.of(STONE));
        VariantAppend.Result r = VariantAppend.append(full, VariantState.of(STONE), VariantState.of(COBBLE), STONE);
        assertFalse(r.accepted());
        assertTrue(r.rejectMessage().contains("full"));
    }
}
