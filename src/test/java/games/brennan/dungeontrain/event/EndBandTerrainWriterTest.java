package games.brennan.dungeontrain.event;

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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one gate every sampled End-band block passes, whichever chunk it is written into. */
class EndBandTerrainWriterTest {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState END_STONE = Blocks.END_STONE.defaultBlockState();
    private static final BlockState OBSIDIAN = Blocks.OBSIDIAN.defaultBlockState();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A sink remembering what was written. */
    private static final class RecordingSink implements EndBandTerrainWriter.Sink {
        final Map<Long, BlockState> cells = new HashMap<>();
        BlockPos blockEntityAt;
        CompoundTag blockEntityNbt;

        @Override
        public BlockState get(int dx, int y, int dz) {
            return cells.getOrDefault(BlockPos.asLong(dx, y, dz), AIR);
        }

        @Override
        public void set(int dx, int y, int dz, BlockState state) {
            cells.put(BlockPos.asLong(dx, y, dz), state);
        }

        @Override
        public void blockEntity(BlockPos at, BlockState state, CompoundTag nbt) {
            blockEntityAt = at;
            blockEntityNbt = nbt;
        }
    }

    @Test
    @DisplayName("a block lands only in air, only where the band keeps it, and never in the reserved lane")
    void admits() {
        assertTrue(EndBandTerrainWriter.admits(false, 1.0, 0.5, AIR), "full band, empty cell");
        assertFalse(EndBandTerrainWriter.admits(true, 1.0, 0.5, AIR), "track lane / train airspace");
        assertFalse(EndBandTerrainWriter.admits(false, 1.0, 0.5, OBSIDIAN), "never over a build");
        assertFalse(EndBandTerrainWriter.admits(false, 0.0, 0.5, AIR), "outside the band");
        assertTrue(EndBandTerrainWriter.admits(false, 0.6, 0.5, AIR), "fade edge: this cell is kept");
        assertFalse(EndBandTerrainWriter.admits(false, 0.4, 0.5, AIR), "fade edge: this cell is thinned out");
    }

    @Test
    @DisplayName("place writes through the sink and hands a block-entity block its sampled NBT")
    void placeWritesAndKeysBlockEntities() {
        SphereCarveGeometry geo = new SphereCarveGeometry(80, 81, 0, 8, 0, 8, 82, 100);
        ChunkPos pos = new ChunkPos(2380, 2);
        RecordingSink sink = new RecordingSink();
        long seed = 8675309031337L;

        assertFalse(EndBandTerrainWriter.place(pos, geo, seed, 1.0, true, false, 3, 80, 4, END_STONE, Map.of(), sink),
                "the rail bed row of the lane is reserved");
        assertTrue(sink.cells.isEmpty());

        assertTrue(EndBandTerrainWriter.place(pos, geo, seed, 1.0, false, false, 3, 90, 12, END_STONE, Map.of(), sink));
        assertEquals(END_STONE, sink.get(3, 90, 12));
        assertNull(sink.blockEntityAt, "end stone has no block entity");

        assertFalse(EndBandTerrainWriter.place(pos, geo, seed, 1.0, false, false, 3, 90, 12, OBSIDIAN, Map.of(), sink),
                "the cell is no longer air");
        assertEquals(END_STONE, sink.get(3, 90, 12));

        BlockPos chestAt = new BlockPos(pos.getMinBlockX() + 5, 91, pos.getMinBlockZ() + 12);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:chest");
        BlockState chest = Blocks.CHEST.defaultBlockState();
        assertTrue(EndBandTerrainWriter.place(pos, geo, seed, 1.0, false, false, 5, 91, 12, chest,
                Map.of(chestAt.asLong(), nbt), sink));
        assertEquals(chestAt, sink.blockEntityAt);
        assertEquals(nbt, sink.blockEntityNbt);
    }
}
