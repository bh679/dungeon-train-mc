package games.brennan.dungeontrain.worldgen;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What one sampled End chunk's features wrote into a <b>neighbouring</b> chunk ({@code endBandFeatureSpill}):
 * the canopy, pillar top, crystal arm or cave carve that crossed the chunk line. Every cell is in
 * <b>display</b> coordinates (already shifted onto track level), with the neighbour's undecorated ground
 * block ({@code before}) and what the feature left there ({@code after}).
 *
 * <p>{@link #shouldWrite} is the whole rule for writing a cell into the live neighbour chunk, and it is
 * the same whether the neighbour got its own terrain before or after this spill was produced — so the
 * order the sampler threads finish in doesn't matter:</p>
 * <ul>
 *   <li>a feature <b>adding</b> a block only lands in a cell that is still air (never over a build, never
 *       over the neighbour's own decoration);</li>
 *   <li>a feature <b>removing</b> a block (a cave or lake carve) only clears a cell that still holds exactly
 *       the ground block it dug through — a build, or a leaf the neighbour's own tree put there, stays.</li>
 * </ul>
 *
 * <p>Pure logic apart from {@link #diff}, which reads two proto chunks; the rest is unit-tested directly.</p>
 */
public record EndBandSpill(long pass, long source, long[] positions, BlockState[] before, BlockState[] after,
                           Map<Long, CompoundTag> blockEntities) {

    public int size() {
        return positions.length;
    }

    /**
     * Whether a column whose End source is pass {@code columnPass} ({@code WorldGenCycle#endSourcePassAt}) may
     * take this spill. Only its own pass: another pass copies a different stretch of the End
     * ({@link EndBandStyle#endChunkOffsetX}), so across a seam where two sampled passes meet the spill would land
     * as the wrong half of someone else's feature.
     */
    public boolean ownedBy(long columnPass) {
        return columnPass == pass;
    }

    /** The display chunk an End chunk maps onto (the inverse of {@link EndBandStyle#endChunkOffsetX}). */
    public static long displayChunkKey(int endChunkX, int endChunkZ, int endChunkOffsetX) {
        return ChunkPos.asLong(endChunkX - endChunkOffsetX, endChunkZ);
    }

    /** Whether a spilled cell may be written over the live block {@code current}. See the class doc. */
    public static boolean shouldWrite(BlockState current, BlockState before, BlockState after) {
        if (after.isAir()) return !current.isAir() && current == before;
        return current.isAir();
    }

    /**
     * Every cell of {@code decorated} (a copy of {@code original} that neighbouring features wrote into)
     * that no longer matches, shifted to display space: X by {@code shiftX}, Y through
     * {@link EndBandStyle#displayY} and clipped to {@code [minY, maxY]}. Block entities the features left
     * come along with their NBT, re-keyed the same way. {@code null} when nothing spilled. {@code pass} and
     * {@code source} (the display chunk key of the sample it came from) travel with it.
     */
    public static EndBandSpill diff(ProtoChunk original, ProtoChunk decorated, int shiftX, int bedY,
                                    int minY, int maxY, RegistryAccess registries, long pass, long source) {
        Builder out = new Builder(pass, source);
        LevelChunkSection[] was = original.getSections();
        LevelChunkSection[] now = decorated.getSections();
        int baseX = decorated.getPos().getMinBlockX() + shiftX;
        int baseZ = decorated.getPos().getMinBlockZ();
        for (int i = 0; i < was.length && i < now.length; i++) {
            LevelChunkSection a = was[i], b = now[i];
            if (a.hasOnlyAir() && b.hasOnlyAir()) continue;
            int sectionY = decorated.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                int dy = EndBandStyle.displayY(sectionY + y, bedY);
                if (dy < minY || dy > maxY) continue;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState before = a.getBlockState(x, y, z);
                        BlockState after = b.getBlockState(x, y, z);
                        if (before == after) continue;
                        out.add(BlockPos.asLong(baseX + x, dy, baseZ + z), before, after);
                    }
                }
            }
        }
        if (out.isEmpty()) return null;
        decorated.getBlockEntityNbts().forEach((at, nbt) -> {
            if (!OfflineChunkSampler.isPlaceholderBlockEntity(nbt)) out.putBlockEntity(at, nbt.copy(), shiftX, bedY, minY, maxY);
        });
        decorated.getBlockEntities().forEach((at, be) ->
                out.putBlockEntity(at, be.saveWithFullMetadata(registries), shiftX, bedY, minY, maxY));
        return out.build();
    }

    /** Collects cells; {@link #build} packs them into an immutable spill. */
    public static final class Builder {
        private final long pass;
        private final long source;
        private final LongArrayList positions = new LongArrayList();
        private final List<BlockState> before = new ArrayList<>();
        private final List<BlockState> after = new ArrayList<>();
        private final Map<Long, CompoundTag> blockEntities = new HashMap<>();

        /** A spill from pass {@code pass}'s sample of display chunk {@code source} (a {@link ChunkPos} key). */
        public Builder(long pass, long source) {
            this.pass = pass;
            this.source = source;
        }

        public void add(long packedDisplayPos, BlockState was, BlockState now) {
            positions.add(packedDisplayPos);
            before.add(was);
            after.add(now);
        }

        /** A block entity at End-space {@code at}, re-keyed to display space (dropped when clipped). */
        public void putBlockEntity(BlockPos at, CompoundTag nbt, int shiftX, int bedY, int minY, int maxY) {
            int y = EndBandStyle.displayY(at.getY(), bedY);
            if (y < minY || y > maxY) return;
            blockEntities.put(BlockPos.asLong(at.getX() + shiftX, y, at.getZ()), nbt);
        }

        public boolean isEmpty() {
            return positions.isEmpty();
        }

        public EndBandSpill build() {
            return new EndBandSpill(pass, source, positions.toLongArray(), before.toArray(new BlockState[0]),
                    after.toArray(new BlockState[0]), Map.copyOf(blockEntities));
        }
    }
}
