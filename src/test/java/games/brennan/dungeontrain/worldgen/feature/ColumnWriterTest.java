package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ColumnWriter}'s block-entity palette gate: the orphan check is skipped for sections whose palette
 * holds no block-entity state, and kept the moment one appears.
 */
final class ColumnWriterTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static PalettedContainer<BlockState> emptySection() {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
    }

    @Test
    @DisplayName("air-only palette gates false")
    void airOnly() {
        assertFalse(ColumnWriter.paletteMayHoldBlockEntity(emptySection()));
    }

    @Test
    @DisplayName("netherrack + lava + stone palette gates false; one chest or spawner gates true")
    void paletteGate() {
        PalettedContainer<BlockState> states = emptySection();
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockState fill = (x + z) % 5 == 0 ? Blocks.LAVA.defaultBlockState()
                            : (x + z) % 7 == 0 ? Blocks.STONE.defaultBlockState()
                            : Blocks.NETHERRACK.defaultBlockState();
                    states.set(x, y, z, fill);
                }
            }
        }
        assertFalse(ColumnWriter.paletteMayHoldBlockEntity(states), "plain rock, lava and air: no orphan possible");

        states.set(3, 9, 4, Blocks.CHEST.defaultBlockState());
        assertTrue(ColumnWriter.paletteMayHoldBlockEntity(states), "a chest in the palette: check each block");

        PalettedContainer<BlockState> spawner = emptySection();
        spawner.set(0, 0, 0, Blocks.SPAWNER.defaultBlockState());
        assertTrue(ColumnWriter.paletteMayHoldBlockEntity(spawner));
    }

    @Test
    @DisplayName("the block-entity predicate matches vanilla for every registered block")
    void predicateMatchesVanilla() {
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            BlockState s = block.defaultBlockState();
            PalettedContainer<BlockState> states = emptySection();
            states.set(1, 1, 1, s);
            assertTrue(ColumnWriter.paletteMayHoldBlockEntity(states) == s.hasBlockEntity() || s.isAir(),
                    "gate disagrees with hasBlockEntity for " + block);
        }
    }
}
