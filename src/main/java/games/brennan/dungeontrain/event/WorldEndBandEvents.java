package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.EndBandSampler;
import games.brennan.dungeontrain.worldgen.EndBandStyle;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.SunlitChunks;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
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

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Writes the BetterEnd End-band passes ({@link EndBandStyle}) in: a chunk in one of those passes
 * generates empty of islands ({@code DisintegrationFeature} skips its stamp there), and the real End
 * terrain {@link EndBandSampler} generated off-thread is written into it here.
 *
 * <ul>
 *   <li><b>Queue</b> — a new band chunk is flagged {@link ModDataAttachments#END_BAND_PENDING} and its
 *       sample requested; a chunk that reloads still flagged asks again.</li>
 *   <li><b>Prefetch</b> — every {@link #PREFETCH_INTERVAL_TICKS} ticks, the strip of band chunks just past
 *       each player's view distance (in the train's +X direction) is requested before it exists. Finished
 *       samples for chunks that aren't loaded yet wait in a small {@link #STASH}, and are written the
 *       moment their chunk generates — so at speed the terrain is there on arrival, not popped in.</li>
 *   <li><b>Apply</b> — raw section writes (the Sable-safe path the other bands use), only into cells that
 *       are <b>still air</b> (nothing a player built is overwritten), clear of the track and the train's
 *       airspace ({@link SphereCarveGeometry}), and thinned across the band's fade edges
 *       ({@link EndBandStyle#keepSampledBlock}). Block entities arrive with their NBT. The chunk is then
 *       re-lit and resent ({@link SunlitChunks}).</li>
 * </ul>
 *
 * <p>Only loaded chunks are ever written ({@code getChunkNow}); nothing here loads or generates a chunk,
 * which keeps it clear of the Sable worldgen deadlock.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldEndBandEvents {

    private static final int PREFETCH_INTERVAL_TICKS = 10;
    /** How many chunk columns past the view distance the prefetch strip reaches. */
    private static final int PREFETCH_DEPTH_CHUNKS = 3;
    /** Finished samples held for chunks that haven't generated yet (~120 KB each). */
    private static final int STASH_CAP = 192;
    /** Chunk-X ahead of a player, measured past its view distance, the prefetch strip starts at. */
    private static final int PREFETCH_LEAD_CHUNKS = 1;

    private static final Set<Heightmap.Types> FULL_HEIGHTMAPS = EnumSet.of(
            Heightmap.Types.WORLD_SURFACE,
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR);

    /** Samples finished before their chunk existed, oldest evicted first. Server thread only. */
    private static final Map<Long, EndBandSampler.Result> STASH = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, EndBandSampler.Result> eldest) {
            return size() > STASH_CAP;
        }
    };

    /** Stashed samples whose chunk has now loaded — written at the start of the next tick. */
    private static final Map<Long, EndBandSampler.Result> DUE = new LinkedHashMap<>();

    private static int tickCounter;

    private WorldEndBandEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        long pass;
        if (event.isNewChunk()) {
            pass = betterEndPass(level, pos);
            if (pass < 0L) return;
            chunk.setData(ModDataAttachments.END_BAND_PENDING, Boolean.TRUE);
            chunk.setUnsaved(true);
        } else {
            // A chunk that unloaded before its terrain arrived: ask again.
            if (!chunk.getData(ModDataAttachments.END_BAND_PENDING)) return;
            pass = WorldGenCycle.fromConfig().endPassIndex(pos.getMinBlockX() + 8);
        }
        EndBandSampler.Result early = STASH.remove(pos.toLong());
        if (early != null) {
            // Prefetched: written on the next tick (never mid-load — a block-entity write here would
            // look the chunk up while it is still being added).
            DUE.put(pos.toLong(), early);
        } else {
            EndBandSampler.request(level, pos, pass, SphereCarveGeometry.of(level).bedY());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;
        if (++tickCounter % PREFETCH_INTERVAL_TICKS == 0) prefetch(level);
        if (!DUE.isEmpty()) {
            // Prefetched terrain for chunks that arrived last tick: all of it, so none is seen bare.
            long t0 = GenProfiler.t0();
            for (EndBandSampler.Result r : DUE.values()) deliver(level, r);
            DUE.clear();
            GenProfiler.add(GenProfiler.Bucket.END_BAND_APPLY, t0);
        }
        int budget = SpheresProgressionConfig.applyPerTick();
        for (int i = 0; i < budget; i++) {
            EndBandSampler.Result r = EndBandSampler.poll();
            if (r == null) return;
            long t0 = GenProfiler.t0();
            deliver(level, r);
            GenProfiler.add(GenProfiler.Bucket.END_BAND_APPLY, t0);
        }
    }

    /** Write {@code r} if its chunk is loaded and still owed; stash it if the chunk isn't there yet. */
    private static void deliver(ServerLevel level, EndBandSampler.Result r) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(r.pos().x, r.pos().z);
        if (chunk == null) {
            STASH.put(r.pos().toLong(), r);                // not generated yet (prefetch) or unloaded
        } else if (chunk.getData(ModDataAttachments.END_BAND_PENDING)) {
            apply(level, chunk, r);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        EndBandSampler.clear();
        games.brennan.dungeontrain.worldgen.BopEnd.clear();
        STASH.clear();
        DUE.clear();
        tickCounter = 0;
    }

    /**
     * The End-band pass index of {@code pos} if it's a sampled (BetterEnd / BoP) pass with any band column in it, else
     * {@code -1}. Every column of one chunk shares a pass: the End band sits mid-cycle, never on a
     * cycle boundary.
     */
    private static long betterEndPass(ServerLevel level, ChunkPos pos) {
        if (DisintegrationBand.startX(level) == DisintegrationBand.OFF) return -1L;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        int minX = pos.getMinBlockX();
        if (cycle.endIslandRamp(minX) <= 0.0 && cycle.endIslandRamp(minX + 15) <= 0.0) return -1L;
        long pass = cycle.endPassIndex(minX + 8);
        return EndBandSampler.appliesTo(level.getServer(), cycle.endStyleOfPass(pass)) ? pass : -1L;
    }

    /** Request the not-yet-generated band chunks just beyond each player's view, ahead in +X. */
    private static void prefetch(ServerLevel level) {
        int view = level.getServer().getPlayerList().getViewDistance();
        int bedY = -1;
        for (ServerPlayer player : level.players()) {
            ChunkPos at = player.chunkPosition();
            for (int ahead = 0; ahead < PREFETCH_DEPTH_CHUNKS; ahead++) {
                int cx = at.x + view + PREFETCH_LEAD_CHUNKS + ahead;
                for (int cz = at.z - view; cz <= at.z + view; cz++) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    if (STASH.containsKey(pos.toLong())) continue;
                    if (level.getChunkSource().getChunkNow(cx, cz) != null) continue;
                    long pass = betterEndPass(level, pos);
                    if (pass < 0L) break;                  // whole strip column shares X: none of it is band
                    if (bedY == -1) bedY = SphereCarveGeometry.of(level).bedY();
                    EndBandSampler.request(level, pos, pass, bedY);
                }
            }
        }
    }

    /** Write one finished sample into its chunk and clear the chunk's pending flag. */
    private static void apply(ServerLevel level, LevelChunk chunk, EndBandSampler.Result r) {
        boolean changed = fill(level, chunk, r);
        chunk.setData(ModDataAttachments.END_BAND_PENDING, Boolean.FALSE);
        chunk.setUnsaved(true);
        if (changed) {
            Heightmap.primeHeightmaps(chunk, FULL_HEIGHTMAPS);
            SunlitChunks.sunlight(level, chunk);           // raw writes skipped the light engine; resends
        }
    }

    private static boolean fill(ServerLevel level, LevelChunk chunk, EndBandSampler.Result r) {
        ChunkPos pos = r.pos();
        SphereCarveGeometry geo = SphereCarveGeometry.of(level);
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
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
                boolean laneZ = geo.laneZ(worldZ), airZ = geo.airZ(worldZ);
                for (int y = yStart; y < yEnd; y++) {
                    BlockState ns = r.stateAt(dx, y, dz);
                    if (ns.isAir() || geo.reserved(y, laneZ, airZ)) continue;
                    if (!EndBandStyle.keepSampledBlock(ramp, Disintegration.coherentNoise(seed, worldX, y, worldZ))) continue;
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

    private static void placeBlockEntity(ServerLevel level, BlockPos at, BlockState state, EndBandSampler.Result r) {
        CompoundTag nbt = r.blockEntities().get(at.asLong());
        BlockEntity be = nbt != null
                ? BlockEntity.loadStatic(at, state, nbt, level.registryAccess())
                : (state.getBlock() instanceof EntityBlock eb ? eb.newBlockEntity(at, state) : null);
        if (be != null) level.setBlockEntity(be);
    }
}
