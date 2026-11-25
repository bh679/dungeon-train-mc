package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.portal.PortalRoomSaveHold;
import games.brennan.dungeontrain.world.TouchedChunks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Which overworld chunks are not written to disk.
 *
 * <h2>Why</h2>
 * <p>Off by default: {@code dropUntouchedWorldGen}, the Performance tab's "Smaller saves".</p>
 *
 * <p>The train drives a long strip of fresh terrain through the world, and vanilla writes all of it:
 * up to 20 chunks a tick while a player is near them, and again when they unload behind the player.
 * Players almost never ride back, so most of a save is terrain nobody will see again. An overworld chunk
 * no player has changed ({@link TouchedChunks}) is not written instead; going back regenerates it from
 * the seed.</p>
 *
 * <h2>When a chunk is still written</h2>
 * <ul>
 *   <li>A player changed it: it is kept as they left it, forever.</li>
 *   <li>A dimensional carriage stands in it ({@link PortalRoomSaveHold}). Each relocation stamps the
 *       room into new chunks that no player has changed; dropped, they came back as empty basement and
 *       the next relocation carried the empty room on.</li>
 *   <li>The server is shutting down and a player was within view distance + {@link #KEEP_MARGIN_CHUNKS}
 *       when it began to ({@link #onServerStopping}; the players are gone by the time the worlds save), so
 *       reopening a world loads where you stood instead of generating it. Measured at the player's real
 *       X/Z, so a dimensional carriage room keeps the chunks around it. Autosaves do not write these: a
 *       crash regenerates them, the same as riding back.</li>
 *   <li>It is behind spawn, where the cycle is mirrored: {@code ReverseSlide} grows over time, so that
 *       terrain would not come back the same.</li>
 *   <li>It has End islands in it: End-band terrain is not deterministic when generated inline.</li>
 * </ul>
 *
 * <p>Nothing already on disk is deleted. A dropped chunk that had been written earlier (before this, or
 * at a shutdown with a player near) keeps that copy, and it is what loads next time. Entities follow the same
 * decision ({@link #entitiesToKeep}) so a regenerated chunk's fresh mobs never stand beside a saved copy
 * of the old ones; only tamed and named mobs are written for a dropped chunk.</p>
 */
public final class UntouchedChunkSaves {

    /** Chunks beyond the server's view distance that are still written while a player is near. */
    public static final int KEEP_MARGIN_CHUNKS = 4;

    /** Most chunks the drop log remembers (~6 MB); see {@link DropLog}. */
    static final int DROP_LOG_CAP = 1 << 18;

    private static final AtomicLong KEPT = new AtomicLong();
    /** Save attempts judged by {@link #shouldSkipSave}, for {@code /dungeontrain debug chunk-saves}. */
    private static final AtomicLong JUDGED = new AtomicLong();
    /** Overworld chunk X/Z of every player as the server began to stop, packed as chunk keys; empty while running. */
    private static volatile long[] stopSpots = new long[0];
    /** Chunks that unloaded unsaved, so a regenerated one can tell it lost what was done to the old copy. */
    private static final DropLog DROPS = new DropLog(DROP_LOG_CAP);
    /** The terrain part of each loaded chunk's verdict. */
    private static final TerrainVerdictCache TERRAIN = new TerrainVerdictCache();

    private UntouchedChunkSaves() {}

    /** Why a chunk is written, or {@link #DROP}. */
    public enum Verdict { DROP, OFF, NOT_OVERWORLD, TOUCHED, PORTAL_ROOM, NEAR_PLAYER, MIRRORED, END_ISLANDS }

    /**
     * Whether {@code ChunkMap#save} should skip {@code chunk}. {@code unloading} is true for the save
     * made as the chunk leaves memory, the only one whose skip means the chunk is really gone: a skipped
     * periodic save leaves it unsaved and in memory, to be judged again later. Main thread.
     */
    public static boolean shouldSkipSave(ServerLevel level, ChunkAccess chunk, boolean unloading) {
        ChunkAccess live = chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : chunk;
        JUDGED.incrementAndGet();
        // The unload save is the one that loses data if it is wrong, so it works the terrain out fresh.
        Verdict verdict = verdict(level, live.getPos(), TouchedChunks.isTouched(level, live),
                unloading ? TerrainVerdictCache.Use.FRESH : TerrainVerdictCache.Use.REMEMBER);
        return settle(verdict, unloading, live.getPos().toLong(), level.getGameTime());
    }

    /** Counts {@code verdict} and, when it drops a chunk that is unloading, logs the drop. Level-free. */
    static boolean settle(Verdict verdict, boolean unloading, long key, long gameTime) {
        boolean drop = verdict == Verdict.DROP;
        if (!drop) KEPT.incrementAndGet();
        else if (unloading) recordDrop(key, gameTime);
        return drop;
    }

    /** Chunk {@code key} has left memory: its cached terrain verdict goes with it. */
    public static void onUnload(long key) {
        TERRAIN.forget(key);
    }

    /** Whether chunk {@code key} unloaded unsaved earlier this session. */
    public static boolean wasDropped(long key) {
        return DROPS.wasDropped(key);
    }

    static void recordDrop(long key, long gameTime) {
        DROPS.record(key, gameTime);
    }

    /** Whether chunk {@code key} unloaded unsaved at or after game time {@code sinceGameTime}. */
    public static boolean droppedSince(long key, long sinceGameTime) {
        return DROPS.droppedSince(key, sinceGameTime);
    }

    /**
     * The entities to write for {@code pos}: all of them when the chunk is written, else only the ones a
     * player would miss (tamed or named). An empty answer means nothing is written for the chunk.
     */
    public static List<Entity> entitiesToKeep(ServerLevel level, ChunkPos pos, List<Entity> entities) {
        // Entities can be written after their chunk unloads, so this reads the terrain cache without filling it.
        if (verdict(level, pos, TouchedChunks.isTouched(level, pos), TerrainVerdictCache.Use.PEEK) != Verdict.DROP) {
            return entities;
        }
        return entities.stream().filter(UntouchedChunkSaves::isPlayersOwn).toList();
    }

    /** {@code use}: how the terrain part reads {@link TerrainVerdictCache}. */
    static Verdict verdict(ServerLevel level, ChunkPos pos, boolean touched, TerrainVerdictCache.Use use) {
        if (!DungeonTrainCommonConfig.isDropUntouchedWorldGen()) return Verdict.OFF;
        if (!level.dimension().equals(Level.OVERWORLD)) return Verdict.NOT_OVERWORLD;
        long key = pos.toLong();
        int keep = level.getServer().getPlayerList().getViewDistance() + KEEP_MARGIN_CHUNKS;
        boolean nearAtStop = !level.getServer().isRunning() && nearAnySpot(stopSpots, pos, keep);
        return liveVerdict(touched, PortalRoomSaveHold.isHeld(key), nearAtStop,
                () -> TERRAIN.get(key, use, k -> terrainVerdict(level, pos)));
    }

    /**
     * The verdict from what can change while a chunk is loaded, read fresh every time, then the terrain,
     * which cannot. Level-free so it is unit-testable.
     */
    static Verdict liveVerdict(boolean touched, boolean held, boolean nearAtStop,
                               java.util.function.Supplier<Verdict> terrain) {
        if (touched) return Verdict.TOUCHED;
        if (held) return Verdict.PORTAL_ROOM;
        if (nearAtStop) return Verdict.NEAR_PLAYER;
        return terrain.get();
    }

    private static Verdict terrainVerdict(ServerLevel level, ChunkPos pos) {
        WorldGenCycle cycle = MixBand.cycleAt(level, pos.x, pos.z);
        return terrainVerdict(cycle.isMirroredAt(pos.getMinBlockX()) || cycle.isMirroredAt(pos.getMaxBlockX()),
                cycle.endIslandRamp(pos.getMinBlockX()) > 0.0 || cycle.endIslandRamp(pos.getMaxBlockX()) > 0.0);
    }

    /** The part of {@link #verdict} that is about the terrain itself; level-free so it is unit-testable. */
    static Verdict terrainVerdict(boolean mirrored, boolean endIslands) {
        if (mirrored) return Verdict.MIRRORED;
        if (endIslands) return Verdict.END_ISLANDS;
        return Verdict.DROP;
    }

    static boolean nearAnySpot(long[] spots, ChunkPos pos, int keepChunks) {
        for (long spot : spots) {
            if (isNear(ChunkPos.getX(spot), ChunkPos.getZ(spot), pos, keepChunks)) return true;
        }
        return false;
    }

    /** Where the overworld's players stand as the server begins to stop, before they are removed. */
    public static void onServerStopping(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        stopSpots = overworld == null ? new long[0] : overworld.players().stream()
                .mapToLong(player -> ChunkPos.asLong(player.getBlockX() >> 4, player.getBlockZ() >> 4))
                .toArray();
    }

    static boolean isNear(int playerChunkX, int playerChunkZ, ChunkPos pos, int keepChunks) {
        return Math.abs(playerChunkX - pos.x) <= keepChunks && Math.abs(playerChunkZ - pos.z) <= keepChunks;
    }

    private static boolean isPlayersOwn(Entity entity) {
        if (entity.hasCustomName()) return true;
        return entity instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null;
    }

    /** Chunks dropped since the server started, for {@code /dungeontrain debug chunk-saves}. */
    public static long dropped() { return DROPS.total(); }

    /** Saves let through since the server started (a chunk is counted every time it is saved). */
    public static long kept() { return KEPT.get(); }

    /** Save attempts judged since the server started. */
    public static long judged() { return JUDGED.get(); }

    /** Terrain verdicts answered from the cache, and worked out, since the server started. */
    public static long terrainCacheHits() { return TERRAIN.hits(); }

    public static long terrainCacheMisses() { return TERRAIN.misses(); }

    public static void resetCounts() {
        KEPT.set(0L);
        JUDGED.set(0L);
        stopSpots = new long[0];
        DROPS.clear();
        TERRAIN.clear();
    }
}
