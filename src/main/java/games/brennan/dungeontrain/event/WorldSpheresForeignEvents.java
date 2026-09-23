package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.FallingBlockAnchor;
import games.brennan.dungeontrain.worldgen.ForeignSphereSampler;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.SphereField;
import games.brennan.dungeontrain.worldgen.SpheresBand;
import games.brennan.dungeontrain.worldgen.SunlitChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The second pass of the spheres-band carve: spheres cut from another dimension, or built around a
 * structure, are left empty by {@link WorldSpheresEvents} and filled here once
 * {@link ForeignSphereSampler} has generated their terrain off-thread.
 *
 * <ul>
 *   <li><b>Queue</b> — at carve time each such sphere's id is recorded on the chunk
 *       ({@link ModDataAttachments#SPHERE_PENDING}) and its sample requested.</li>
 *   <li><b>Apply</b> — each server tick up to {@code spheresForeignApplyPerTick} finished samples are
 *       written into their chunk with raw section writes (the Sable-safe path the carve uses), only into
 *       cells the sphere owns that are <b>still air</b> — so nothing a player has built is overwritten —
 *       then the chunk is re-lit and resent ({@link SunlitChunks}). Block entities arrive with their
 *       NBT, so a structure's chests keep their loot tables.</li>
 *   <li><b>Persist</b> — a chunk that unloads before its sample arrives keeps its pending ids and is
 *       re-queued on its next load. An id is cleared once written, so a sphere is filled exactly once:
 *       a player who digs one out keeps the hole.</li>
 * </ul>
 *
 * <p>Only loaded chunks are ever written ({@code getChunkNow}); nothing here loads or generates a chunk,
 * which is what keeps it clear of the Sable worldgen deadlock.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldSpheresForeignEvents {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

    private static final Set<Heightmap.Types> FULL_HEIGHTMAPS = EnumSet.of(
            Heightmap.Types.WORLD_SURFACE,
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR);

    private WorldSpheresForeignEvents() {}

    /** Record {@code spheres} as owed to {@code chunk} and request their samples. Server thread. */
    static void queue(ServerLevel level, ChunkAccess chunk, List<SphereField.Sphere> spheres) {
        if (spheres.isEmpty()) return;
        List<Long> pending = new ArrayList<>(chunk.getData(ModDataAttachments.SPHERE_PENDING));
        for (SphereField.Sphere s : spheres) {
            if (!pending.contains(s.id())) pending.add(s.id());
        }
        chunk.setData(ModDataAttachments.SPHERE_PENDING, List.copyOf(pending));
        chunk.setUnsaved(true);
        request(level, chunk.getPos(), spheres);
    }

    private static void request(ServerLevel level, ChunkPos pos, List<SphereField.Sphere> spheres) {
        long seed = DungeonTrainWorldData.get(level).getGenerationSeed();
        for (SphereField.Sphere s : spheres) ForeignSphereSampler.request(level, s, pos, seed);
    }

    /** A chunk reloaded with spheres still owed: ask for them again. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.isNewChunk()) return;                          // a new chunk queues from its carve
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        ChunkAccess chunk = event.getChunk();
        List<Long> pending = chunk.getData(ModDataAttachments.SPHERE_PENDING);
        if (pending.isEmpty()) return;
        ChunkPos pos = chunk.getPos();
        List<SphereField.Sphere> owed = new ArrayList<>(pending.size());
        for (SphereField.Sphere s : SpheresBand.candidates(level, pos.x, pos.z)) {
            if (pending.contains(s.id())) owed.add(s);
        }
        if (owed.size() != pending.size()) {
            // The band moved or its spheres changed (config edit): forget spheres that no longer exist.
            List<Long> kept = new ArrayList<>(owed.size());
            for (SphereField.Sphere s : owed) kept.add(s.id());
            chunk.setData(ModDataAttachments.SPHERE_PENDING, List.copyOf(kept));
            chunk.setUnsaved(true);
        }
        request(level, pos, owed);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;
        int budget = SpheresProgressionConfig.applyPerTick();
        for (int i = 0; i < budget; i++) {
            ForeignSphereSampler.Result r = ForeignSphereSampler.poll();
            if (r == null) return;
            long t0 = GenProfiler.t0();
            apply(level, r);
            GenProfiler.add(GenProfiler.Bucket.SPHERES_FOREIGN_APPLY, t0);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ForeignSphereSampler.clear();
    }

    /** Write one finished sample into its chunk, if it is loaded and still owes that sphere. */
    private static void apply(ServerLevel level, ForeignSphereSampler.Result r) {
        ChunkPos pos = r.pos();
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) return;                               // unloaded: stays pending, re-queued on load
        List<Long> pending = chunk.getData(ModDataAttachments.SPHERE_PENDING);
        SphereField.Sphere sphere = r.sphere();
        if (!pending.contains(sphere.id())) return;              // already filled

        boolean changed = fill(level, chunk, r);

        List<Long> rest = new ArrayList<>(pending);
        rest.remove(sphere.id());
        chunk.setData(ModDataAttachments.SPHERE_PENDING, List.copyOf(rest));
        chunk.setUnsaved(true);
        if (changed) {
            Heightmap.primeHeightmaps(chunk, FULL_HEIGHTMAPS);
            SunlitChunks.sunlight(level, chunk);                  // raw writes skipped the light engine; resends
        }
    }

    private static boolean fill(ServerLevel level, LevelChunk chunk, ForeignSphereSampler.Result r) {
        SphereField.Sphere sphere = r.sphere();
        ChunkPos pos = r.pos();
        SphereCarveGeometry geo = SphereCarveGeometry.of(level);
        List<SphereField.Sphere> candidates = SpheresBand.candidates(level, pos.x, pos.z);
        int yEnd = Math.min(r.minY() + r.height(), chunk.getMaxBuildHeight());
        boolean changed = false;
        for (int dx = 0; dx < 16; dx++) {
            int worldX = pos.getMinBlockX() + dx;
            for (int dz = 0; dz < 16; dz++) {
                int worldZ = pos.getMinBlockZ() + dz;
                if (!sphere.touchesColumn(worldX, worldZ)) continue;
                boolean laneZ = geo.laneZ(worldZ), airZ = geo.airZ(worldZ);
                for (int y = Math.max(r.minY(), chunk.getMinBuildHeight()); y < yEnd; y++) {
                    if (geo.reserved(y, laneZ, airZ)) continue;
                    SphereField.Sphere owner = SphereField.bestAt(candidates, worldX, y, worldZ);
                    if (owner == null || owner.id() != sphere.id()) continue;
                    BlockState ns = settled(r.stateAt(dx, y, dz));
                    if (ns.isAir()) continue;
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                    if (!section.getBlockState(dx, y & 15, dz).isAir()) continue;   // never over a build
                    section.setBlockState(dx, y & 15, dz, ns, false);
                    if (ns.hasBlockEntity()) placeBlockEntity(level, new BlockPos(worldX, y, worldZ), ns, r);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * A sampled block as it should rest in a floating sphere: liquids become still sources (the
     * geometric fluid veto keeps them from pouring into the void), fallables their stable equivalent.
     * Unlike the inline carve's {@code lifted}, block-entity blocks are kept — their NBT travelled
     * with the sample.
     */
    private static BlockState settled(BlockState source) {
        if (source.isAir()) return AIR;
        if (source.getBlock() instanceof LiquidBlock) {
            return source.getFluidState().is(FluidTags.WATER) ? WATER : LAVA;
        }
        BlockState stable = FallingBlockAnchor.stableEquivalent(source);
        return stable != null ? stable : source;
    }

    private static void placeBlockEntity(ServerLevel level, BlockPos at, BlockState state,
                                         ForeignSphereSampler.Result r) {
        CompoundTag nbt = r.blockEntities().get(at.asLong());
        BlockEntity be = nbt != null
                ? BlockEntity.loadStatic(at, state, nbt, level.registryAccess())
                : (state.getBlock() instanceof EntityBlock eb ? eb.newBlockEntity(at, state) : null);
        if (be != null) level.setBlockEntity(be);
    }
}
