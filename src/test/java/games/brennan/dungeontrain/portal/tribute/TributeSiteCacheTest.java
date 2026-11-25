package games.brennan.dungeontrain.portal.tribute;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TributeSiteCacheTest {

    @Test
    void keptChunksAreEveryChunkTheStampWaitsOn() {
        BoundingBox window = new BoundingBox(-4, -63, -516, 40, -51, -502);
        List<ChunkPos> chunks = TributeSiteCache.chunksOf(window);
        assertEquals(TributeRoomVisitTickets.waitedOn(window), chunks.size());
        assertTrue(chunks.contains(new ChunkPos(-2, -34)));
        assertTrue(chunks.contains(new ChunkPos(3, -31)));
        for (ChunkPos p : chunks) assertTrue(TributeRoomVisitSite.isSiteChunk(p.x, p.z), "kept but not saved: " + p);
    }

    @Test
    void portableChunkLeavesTheWorldItCameFromBehind() {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("xPos", 1);
        chunk.putInt("zPos", -32);
        chunk.putString("Status", "minecraft:full");
        chunk.put("sections", new net.minecraft.nbt.ListTag());
        chunk.put("structures", new CompoundTag());
        chunk.put("block_ticks", new net.minecraft.nbt.ListTag());
        chunk.put("neoforge:attachments", new CompoundTag());
        chunk.putLong("InhabitedTime", 5000L);

        CompoundTag out = TributeSiteCache.portable(chunk);
        assertFalse(out.contains("structures"));
        assertFalse(out.contains("block_ticks"));
        assertFalse(out.contains("neoforge:attachments"));
        assertEquals(0L, out.getLong("InhabitedTime"));
        assertEquals(1, out.getInt("xPos"));
        assertEquals("minecraft:full", out.getString("Status"));
        assertTrue(out.contains("sections"));
        assertTrue(chunk.contains("structures"), "the source is left alone");
    }
}
