package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.ChunkMapChunksInvoker;
import games.brennan.dungeontrain.mixin.ChunkMapSpawnRangeInvoker;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Random ticks for the chunks around a player inside a dimensional carriage.
 *
 * <h2>Why</h2>
 * <p>{@link PortalTwinChunkHold} makes vanilla treat a player in twin space like a spectator
 * ({@code ChunkMap#skipPlayer}), which takes them out of the distance manager's player count. Vanilla's
 * {@code ServerChunkCache#tickChunks} random-ticks a chunk only when
 * {@code ChunkMap#anyPlayerCloseEnoughForSpawning} says a player is near, so a room whose only visitor is
 * held got no random ticks at all: lit redstone ore saved into a template stayed lit forever, crops
 * never grew, grass never spread. Natural spawning sits behind the same check and stays off; only the
 * random ticks come back.</p>
 *
 * <h2>What</h2>
 * <p>Once per level tick, every chunk within vanilla's spawning range (128 blocks, chunk centre to
 * player) of a held player that vanilla skipped for want of a player, but which passes every other gate
 * vanilla checks, gets {@link #randomTickChunk}: a copy of the random-tick half of
 * {@code ServerLevel#tickChunk}. Same {@code randomTickSpeed}, same per-section sampling, so timings
 * are vanilla's and a world at {@code randomTickSpeed 0} (the editor) stays still. A chunk vanilla is
 * already ticking, because somebody on the train overhead is near, is left to vanilla.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalTwinRandomTicks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Vanilla's {@code ChunkMap#playerIsCloseEnoughForSpawning}: 128 blocks, squared. */
    static final double SPAWN_RANGE_SQ = 16384.0;

    /** Chunks either side of the player to consider; 128 blocks plus a chunk of slack. */
    private static final int CHUNK_REACH = 9;

    /** How often the ticked-chunk count is logged at debug, in ticks. */
    private static final int LOG_EVERY = 600;

    /** Per held player, their last reach square. Weak keys: a player who leaves or respawns drops out. */
    private static final Map<ServerPlayer, Reach> REACH = new WeakHashMap<>();

    /** Chunks already ticked this pass, used only with two or more held players. Server thread only. */
    private static final LongSet SEEN = new LongOpenHashSet();

    private static long chunksTicked;
    private static long lastLogAt;

    private PortalTwinRandomTicks() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // Nearly every tick nobody is in a room: stop before allocating anything.
        if (!PortalTwinChunkHold.anyHeld()) return;
        List<ServerPlayer> held = heldPlayers(level);
        int speed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        if (!passRuns(level.tickRateManager().runsNormally(), speed, held.size())) return;
        boolean dedupe = held.size() > 1;
        if (dedupe) SEEN.clear();
        for (ServerPlayer player : held) {
            for (ChunkPos pos : reachFor(player)) {
                if (!inPlayerRange(pos.x, pos.z, player.getX(), player.getZ())) continue;
                if (dedupe && !SEEN.add(pos.toLong())) continue;
                LevelChunk chunk = tickableChunk(level, pos);
                if (chunk == null) continue;
                randomTickChunk(level, chunk, speed);
                chunksTicked++;
            }
        }
        logNow(level);
    }

    private static List<ServerPlayer> heldPlayers(ServerLevel level) {
        List<ServerPlayer> held = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (PortalTwinChunkHold.isHeld(player)) held.add(player);
        }
        return held;
    }

    /** The reach square around {@code player}'s chunk, rebuilt only when they cross into another chunk. */
    private static ChunkPos[] reachFor(ServerPlayer player) {
        int pcx = SectionPos.blockToSectionCoord(player.getBlockX());
        int pcz = SectionPos.blockToSectionCoord(player.getBlockZ());
        Reach next = Reach.forChunk(REACH.get(player), pcx, pcz);
        REACH.put(player, next);
        return next.chunks();
    }

    /**
     * The {@code (2 * CHUNK_REACH + 1)}-square of chunks around one player chunk. Only the chunk
     * coordinates are cached: the range test still runs every tick against the player's exact position,
     * because which edge chunks are in range changes as the player moves inside a chunk.
     */
    record Reach(int cx, int cz, ChunkPos[] chunks) {

        /** {@code previous} again when it is for this chunk, else a fresh square. */
        static Reach forChunk(Reach previous, int cx, int cz) {
            if (previous != null && previous.cx == cx && previous.cz == cz) return previous;
            int side = 2 * CHUNK_REACH + 1;
            ChunkPos[] chunks = new ChunkPos[side * side];
            int i = 0;
            for (int x = cx - CHUNK_REACH; x <= cx + CHUNK_REACH; x++) {
                for (int z = cz - CHUNK_REACH; z <= cz + CHUNK_REACH; z++) {
                    chunks[i++] = new ChunkPos(x, z);
                }
            }
            return new Reach(cx, cz, chunks);
        }
    }

    /**
     * Pure core: whether the pass runs at all this tick. Vanilla's whole spawn-and-tick block sits
     * behind {@code runsNormally}, so {@code /tick freeze} stops these too; {@code randomTickSpeed 0}
     * (the editor world) means nothing random-ticks; and with nobody held there is nothing to add.
     */
    static boolean passRuns(boolean runsNormally, int randomTickSpeed, int heldPlayers) {
        return runsNormally && randomTickSpeed > 0 && heldPlayers > 0;
    }

    /** Vanilla's {@code ChunkMap#euclideanDistanceSquared} against the spawning range. */
    static boolean inPlayerRange(int chunkX, int chunkZ, double playerX, double playerZ) {
        double dx = SectionPos.sectionToBlockCoord(chunkX, 8) - playerX;
        double dz = SectionPos.sectionToBlockCoord(chunkZ, 8) - playerZ;
        return dx * dx + dz * dz < SPAWN_RANGE_SQ;
    }

    /**
     * Pure core: whether this pass should random-tick a chunk. Every gate vanilla's chunk loop applies
     * must pass except the player check, and vanilla must not be ticking it already (a player near, or
     * a NeoForge force-ticked chunk).
     */
    static boolean shouldTick(boolean tickingChunk, boolean spawningAllowed, boolean blocksTick,
                              boolean vanillaTicks) {
        return tickingChunk && spawningAllowed && blocksTick && !vanillaTicks;
    }

    private static LevelChunk tickableChunk(ServerLevel level, ChunkPos pos) {
        ChunkMap map = level.getChunkSource().chunkMap;
        ChunkHolder holder = ((ChunkMapChunksInvoker) map).dungeontrain$getVisibleChunkIfPresent(pos.toLong());
        LevelChunk chunk = holder == null ? null : holder.getTickingChunk();
        if (chunk == null) return null;
        // Vanilla ticks the chunk itself when a player is near OR NeoForge force-ticks it.
        boolean vanillaTicks = ((ChunkMapSpawnRangeInvoker) map).dungeontrain$anyPlayerCloseEnoughForSpawning(pos)
            || map.getDistanceManager().shouldForceTicks(pos.toLong());
        boolean tick = shouldTick(true,
            level.isNaturalSpawningAllowed(pos),
            level.shouldTickBlocksAt(pos.toLong()),
            vanillaTicks);
        return tick ? chunk : null;
    }

    /** The random-tick half of vanilla {@code ServerLevel#tickChunk}, unchanged. */
    private static void randomTickChunk(ServerLevel level, LevelChunk chunk, int speed) {
        ChunkPos pos = chunk.getPos();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!section.isRandomlyTicking()) continue;
            int minY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            for (int n = 0; n < speed; n++) {
                BlockPos at = level.getBlockRandomPos(minX, minY, minZ, 15);
                BlockState state = section.getBlockState(at.getX() - minX, at.getY() - minY, at.getZ() - minZ);
                if (state.isRandomlyTicking()) state.randomTick(level, at, level.random);
                FluidState fluid = state.getFluidState();
                if (fluid.isRandomlyTicking()) fluid.randomTick(level, at, level.random);
            }
        }
    }

    private static void logNow(ServerLevel level) {
        long now = level.getGameTime();
        if (now - lastLogAt < LOG_EVERY) return;
        lastLogAt = now;
        LOGGER.debug("[DT-TwinTicks] {} random-ticked {} room chunk-ticks so far",
            level.dimension().location(), chunksTicked);
    }
}
