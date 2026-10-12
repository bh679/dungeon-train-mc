package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.EndBandSampler;
import games.brennan.dungeontrain.worldgen.EndBandStyle;
import games.brennan.dungeontrain.worldgen.MixBand;
import games.brennan.dungeontrain.worldgen.SampledCells;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;

import java.util.Map;

/**
 * Writes a sampled End-band chunk ({@link EndBandSampler.Result}) into a chunk — the one rule for both
 * places that happens: into the live chunk on the server thread ({@link WorldEndBandEvents}, the
 * background path) and into the proto chunk during its own worldgen ({@link EndBandInlineTerrain}).
 *
 * <p>The gate every sampled block passes ({@link #admits}): clear of the track lane and the train's
 * airspace ({@link SphereCarveGeometry#reserved}), thinned across the band's fade edges
 * ({@link EndBandStyle#keepSampledBlock}), and only into a cell that is still air — never over a build,
 * never over what the display world already placed. Across a joined End band's seam only the columns the
 * sampled look owns are written ({@link #sampledOwns}). Block entities arrive with their sampled NBT, or
 * are created fresh when the sample carried none.</p>
 */
final class EndBandTerrainWriter {

    /** Where the blocks go: chunk-local coordinates, world Y. */
    interface Sink {
        BlockState get(int dx, int y, int dz);

        void set(int dx, int y, int dz, BlockState state);

        /** A block-entity block was written at {@code at}; {@code nbt} is its sampled data or {@code null}. */
        void blockEntity(BlockPos at, BlockState state, CompoundTag nbt);
    }

    private EndBandTerrainWriter() {}

    /**
     * Whether a sampled block may land in a cell: not reserved for the track or the train, kept by the
     * band's fade ({@code noise01} against {@code ramp}), and the cell still air. Pure.
     */
    static boolean admits(boolean reserved, double ramp, double noise01, BlockState current) {
        if (reserved) return false;
        if (!EndBandStyle.keepSampledBlock(ramp, noise01)) return false;
        return current.isAir();
    }

    /** Write every admitted block of {@code r} through {@code sink}; true if anything was written. */
    static boolean write(ServerLevel level, ChunkAccess chunk, EndBandSampler.Result r, Sink sink) {
        ChunkPos pos = r.pos();
        SphereCarveGeometry geo = SphereCarveGeometry.of(level);
        WorldGenCycle cycle = MixBand.cycleAt(level, pos.x, pos.z);    // mix zone: the chunk's picked band
        long seed = DungeonTrainWorldData.get(level).getGenerationSeed();
        int yStart = Math.max(r.minY(), chunk.getMinBuildHeight());
        int yEnd = Math.min(r.minY() + r.height(), chunk.getMaxBuildHeight());
        boolean changed = false;
        for (int dx = 0; dx < 16; dx++) {
            int worldX = pos.getMinBlockX() + dx;
            double ramp = cycle.endIslandRamp(worldX);
            if (ramp <= 0.0) continue;
            for (int dz = 0; dz < 16; dz++) {
                int worldZ = pos.getMinBlockZ() + dz;
                if (!sampledOwns(level, cycle, seed, worldX, worldZ)) continue;
                boolean laneZ = geo.laneZ(worldZ), airZ = geo.airZ(worldZ);
                for (int y = yStart; y < yEnd; y++) {
                    BlockState ns = r.stateAt(dx, y, dz);
                    if (ns.isAir()) continue;
                    changed |= place(pos, geo, seed, ramp, laneZ, airZ, dx, y, dz, ns, r.blockEntities(), sink);
                }
            }
        }
        return changed;
    }

    /** One sampled block through the gate. True if it was written. */
    static boolean place(ChunkPos pos, SphereCarveGeometry geo, long seed, double ramp, boolean laneZ, boolean airZ,
                         int dx, int y, int dz, BlockState ns, Map<Long, CompoundTag> blockEntities, Sink sink) {
        if (geo.reserved(y, laneZ, airZ)) return false;
        int worldX = pos.getMinBlockX() + dx, worldZ = pos.getMinBlockZ() + dz;
        if (!admits(false, ramp, Disintegration.coherentNoise(seed, worldX, y, worldZ), sink.get(dx, y, dz))) return false;
        sink.set(dx, y, dz, ns);
        if (ns.hasBlockEntity()) {
            BlockPos at = new BlockPos(worldX, y, worldZ);
            sink.blockEntity(at, ns, blockEntities.get(at.asLong()));
        }
        return true;
    }

    /** Whether the sampled look owns column {@code (worldX, worldZ)}: across a joined End band's seam the stamped vanilla look owns some. */
    static boolean sampledOwns(ServerLevel level, WorldGenCycle cycle, long seed, int worldX, int worldZ) {
        return EndBandSampler.appliesTo(level.getServer(), cycle.endSourceLookAt(worldX, worldZ, seed));
    }

    /** The block entity for a sampled block: loaded from its NBT, else created fresh. */
    static BlockEntity blockEntityFor(BlockPos at, BlockState state, CompoundTag nbt, RegistryAccess registries) {
        if (nbt != null) return BlockEntity.loadStatic(at, state, nbt, registries);
        return state.getBlock() instanceof EntityBlock eb ? eb.newBlockEntity(at, state) : null;
    }

    /**
     * Raw section writes into a loaded chunk (the Sable-safe path the other bands use); the caller re-primes
     * heightmaps and relights afterwards. Block entities go through the level so they tick.
     */
    static Sink liveSink(ServerLevel level, LevelChunk chunk) {
        return new Sink() {
            @Override
            public BlockState get(int dx, int y, int dz) {
                return chunk.getSection(chunk.getSectionIndex(y)).getBlockState(dx, y & 15, dz);
            }

            @Override
            public void set(int dx, int y, int dz, BlockState state) {
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                section.setBlockState(dx, y & 15, dz, state, false);
            }

            @Override
            public void blockEntity(BlockPos at, BlockState state, CompoundTag nbt) {
                BlockEntity be = blockEntityFor(at, state, nbt, level.registryAccess());
                if (be != null) level.setBlockEntity(be);
            }
        };
    }

    /** {@code inner}, with every cell it writes also marked in {@code cells} (for the void erosion to skip). */
    static Sink recordingSink(Sink inner, SampledCells cells) {
        return new Sink() {
            @Override
            public BlockState get(int dx, int y, int dz) {
                return inner.get(dx, y, dz);
            }

            @Override
            public void set(int dx, int y, int dz, BlockState state) {
                inner.set(dx, y, dz, state);
                cells.mark(dx, y, dz);
            }

            @Override
            public void blockEntity(BlockPos at, BlockState state, CompoundTag nbt) {
                inner.blockEntity(at, state, nbt);
            }
        };
    }

    /**
     * Writes into a chunk still being generated: {@code ProtoChunk.setBlockState} keeps its heightmaps
     * current, and block entities are handed to the chunk, which promotes them when it goes live.
     */
    static Sink protoSink(ProtoChunk chunk, RegistryAccess registries) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        return new Sink() {
            @Override
            public BlockState get(int dx, int y, int dz) {
                return chunk.getBlockState(cursor.set(baseX + dx, y, baseZ + dz));
            }

            @Override
            public void set(int dx, int y, int dz, BlockState state) {
                chunk.setBlockState(new BlockPos(baseX + dx, y, baseZ + dz), state, false);
            }

            @Override
            public void blockEntity(BlockPos at, BlockState state, CompoundTag nbt) {
                BlockEntity be = blockEntityFor(at, state, nbt, registries);
                if (be != null) chunk.setBlockEntity(be);
            }
        };
    }
}
