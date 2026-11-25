package games.brennan.dungeontrain.portal;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a decoration pass is said to have changed, and when a room can be patched with it. */
class PortalChunkDecorationDiffTest {

    private static final int W = 4;
    private static final int H = 3;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static BlockState[] stone() {
        BlockState[] states = new BlockState[W * W * H];
        java.util.Arrays.fill(states, Blocks.STONE.defaultBlockState());
        return states;
    }

    private static PortalChunkSlice slice(BlockState[] states, Map<Integer, CompoundTag> blockEntities) {
        return new PortalChunkSlice(PortalChunkTerrain.Source.OVERWORLD, W, H, states, blockEntities, List.of());
    }

    @Test
    @DisplayName("Changed states are listed bottom-up and nothing else is")
    void changedStates() {
        PortalChunkSlice ground = slice(stone(), Map.of());
        BlockState[] grown = stone();
        grown[40] = Blocks.OAK_LOG.defaultBlockState();
        grown[2] = Blocks.GRASS_BLOCK.defaultBlockState();
        PortalChunkSlice decorated = PortalChunkDecorationDiff.attach(ground, slice(grown, Map.of()));

        assertTrue(decorated.decorates(ground));
        assertArrayEquals(new int[] {2, 40}, decorated.changes());
        assertFalse(decorated.biomesChanged(), "neither side carries biomes: the same");
        assertEquals(Blocks.OAK_LOG.defaultBlockState(), decorated.at(decorated.xOf(40), decorated.yOf(40), decorated.zOf(40)));
        assertEquals(40 / W, decorated.stripOf(40));
    }

    @Test
    @DisplayName("A chest that gained its loot table is a change even though its block is the same")
    void changedBlockEntity() {
        BlockState[] states = stone();
        states[7] = Blocks.CHEST.defaultBlockState();
        CompoundTag loot = new CompoundTag();
        loot.putString("LootTable", "minecraft:chests/village/village_plains_house");
        PortalChunkSlice ground = slice(states, Map.of(7, new CompoundTag()));
        PortalChunkSlice decorated = PortalChunkDecorationDiff.attach(ground, slice(states.clone(), Map.of(7, loot)));
        assertArrayEquals(new int[] {7}, decorated.changes());

        PortalChunkSlice same = PortalChunkDecorationDiff.attach(ground, slice(states.clone(), Map.of(7, new CompoundTag())));
        assertArrayEquals(new int[0], same.changes());
    }

    @Test
    @DisplayName("Without a ground, or against another slice, there is nothing to patch with")
    void noGround() {
        PortalChunkSlice decorated = slice(stone(), Map.of());
        assertSame(decorated, PortalChunkDecorationDiff.attach(null, decorated));
        assertFalse(decorated.decorates(slice(stone(), Map.of())));
        PortalChunkSlice ground = slice(stone(), Map.of());
        PortalChunkSlice diffed = PortalChunkDecorationDiff.attach(ground, decorated);
        assertFalse(diffed.decorates(slice(stone(), Map.of())), "identity, not content");
        assertTrue(diffed.decorates(ground));
    }
}
