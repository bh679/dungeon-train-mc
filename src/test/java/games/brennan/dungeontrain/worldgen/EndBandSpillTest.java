package games.brennan.dungeontrain.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The End-band feature spill's pure rules: where a ring chunk lands in display space, and what may be written. */
class EndBandSpillTest {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState END_STONE = Blocks.END_STONE.defaultBlockState();
    private static final BlockState LEAVES = Blocks.OAK_LEAVES.defaultBlockState();
    private static final BlockState OBSIDIAN = Blocks.OBSIDIAN.defaultBlockState();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("a ring End chunk maps back onto the display chunk the pass offset put it at")
    void displayChunkKeyInvertsThePassOffset() {
        int offset = EndBandStyle.endChunkOffsetX(1);
        ChunkPos display = new ChunkPos(2313, 4);
        ChunkPos endPos = new ChunkPos(display.x + offset, display.z);
        assertEquals(new ChunkPos(display.x + 1, display.z - 1).toLong(),
                EndBandSpill.displayChunkKey(endPos.x + 1, endPos.z - 1, offset));
        assertEquals(new ChunkPos(display.x - 1, display.z + 1).toLong(),
                EndBandSpill.displayChunkKey(endPos.x - 1, endPos.z + 1, offset));
    }

    @Test
    @DisplayName("an added block only lands in a cell that is still air")
    void addsOnlyIntoAir() {
        assertTrue(EndBandSpill.shouldWrite(AIR, AIR, LEAVES), "canopy into open air");
        assertFalse(EndBandSpill.shouldWrite(END_STONE, AIR, LEAVES), "never over ground");
        assertFalse(EndBandSpill.shouldWrite(OBSIDIAN, AIR, LEAVES), "never over a build or the neighbour's own decoration");
    }

    @Test
    @DisplayName("a carve only clears the exact ground block it dug through")
    void carvesOnlyTheGroundItDugThrough() {
        assertTrue(EndBandSpill.shouldWrite(END_STONE, END_STONE, AIR), "the cave bowl continues into the neighbour");
        assertFalse(EndBandSpill.shouldWrite(OBSIDIAN, END_STONE, AIR), "a build stays");
        assertFalse(EndBandSpill.shouldWrite(LEAVES, END_STONE, AIR), "the neighbour's own leaf stays");
        assertFalse(EndBandSpill.shouldWrite(AIR, END_STONE, AIR), "nothing to clear");
    }

    @Test
    @DisplayName("the builder packs cells and re-keys block entities to display space, clipping the Y window")
    void builderPacksAndReKeys() {
        EndBandSpill.Builder b = new EndBandSpill.Builder(3L, ChunkPos.asLong(2313, 4));
        assertTrue(b.isEmpty());
        long cell = BlockPos.asLong(37008, 90, 70);
        b.add(cell, END_STONE, AIR);
        b.add(cell + 1, AIR, LEAVES);
        int bedY = 80;
        int shiftX = -1000;
        int insideEndY = EndBandStyle.endY(90, bedY);
        int belowEndY = EndBandStyle.endY(10, bedY);
        b.putBlockEntity(new BlockPos(38008, insideEndY, 70), new CompoundTag(), shiftX, bedY, 20, 200);
        b.putBlockEntity(new BlockPos(38008, belowEndY, 70), new CompoundTag(), shiftX, bedY, 20, 200);
        EndBandSpill spill = b.build();
        assertEquals(2, spill.size());
        assertArrayEquals(new long[] {cell, cell + 1}, spill.positions());
        assertEquals(END_STONE, spill.before()[0]);
        assertEquals(LEAVES, spill.after()[1]);
        assertEquals(1, spill.blockEntities().size(), "the clipped block entity is dropped");
        assertTrue(spill.blockEntities().containsKey(BlockPos.asLong(37008, 90, 70)));
        assertNull(spill.blockEntities().get(BlockPos.asLong(37008, 10, 70)));
        assertEquals(3L, spill.pass(), "the pass travels with the spill");
        assertEquals(ChunkPos.asLong(2313, 4), spill.source(), "and the chunk it came from");
    }

    @Test
    @DisplayName("spill only lands in columns of its own pass — never another sampled pass across a seam")
    void onlyItsOwnPassTakesIt() {
        EndBandSpill spill = new EndBandSpill.Builder(3L, 0L).build();
        assertTrue(spill.ownedBy(3L), "its own pass");
        assertFalse(spill.ownedBy(2L), "the pass on the other side of a joined seam copies another stretch of the End");
        assertFalse(spill.ownedBy(-1L), "no End pass at all");
    }
}
