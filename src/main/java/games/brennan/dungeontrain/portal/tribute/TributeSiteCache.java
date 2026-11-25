package games.brennan.dungeontrain.portal.tribute;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.perks.PerksGate;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The first visit site's chunks, kept outside any world so no world has to generate them on a death.
 *
 * <p>Generating the site's chunks is what a visit waits on: seconds in a settled world, over twenty in
 * a young one where the train is generating terrain too. The site sits under the bedrock, where nothing
 * but the visit ever changes, so its chunks are the same in every world. They are generated once, in the
 * background when a perks world starts, and saved here ({@code <gameDir>/dungeontrain/tribute-site/}).
 * Every world after that gets them written into its own chunk storage at start, wherever it has none, and
 * a death loads them from disk.</p>
 *
 * <p>Only slot 0 is kept: one visitor at a time is the singleplayer case and the common server one. The
 * chunks are whole columns, so a world's surface over the site shows the terrain of the world they were
 * generated in; the site is 100,000 blocks off the track ({@link TributeRoomVisitSite#Z_BAND}). World-specific data is left out: structure starts
 * and references, scheduled ticks and attachments. Mobs live in the entity storage and are not copied.</p>
 */
public final class TributeSiteCache {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUB_DIR = "tribute-site";
    private static final String FILE = "slot0.nbt";
    private static final int FORMAT = 1;
    /** Chunks serialized per tick while caching, so the capture is never one long tick. */
    private static final int CHUNKS_PER_TICK = 4;
    /** The warm-up's own ticket holder, apart from any player's. */
    private static final UUID WARM_ID = UUID.fromString("7c1b0f4e-2d5a-4f0e-9a51-7b1d0c2e3f40");
    /** Chunk-level keys a world's saved chunk may not carry into another world. */
    private static final List<String> WORLD_SPECIFIC = List.of("structures", "block_ticks", "fluid_ticks",
        "PostProcessing", "blending_data", "below_zero_retrogen", "UpgradeData", "neoforge:attachments");

    /** A warm-up under way in the running world: its window, and the chunks still to serialize. */
    private record Warm(BoundingBox window, CompoundTag fingerprint, List<ChunkPos> pending, ListTag captured, long startedNs) {}

    @Nullable private static volatile Warm warm;
    private static volatile boolean warmTicketHeld;

    private TributeSiteCache() {}

    static Path file() {
        return PlayerDataPaths.dir(SUB_DIR).resolve(FILE);
    }

    /**
     * A perks world started: give it the cached site, or start caching one. Reads on the IO thread,
     * writes into the world's chunk storage before anything asks for those chunks.
     */
    public static void onServerStarted(MinecraftServer server) {
        warm = null;
        warmTicketHeld = false;
        if (!PerksGate.isOn() || server.isHardcore()) return;
        ServerLevel overworld = server.overworld();
        TributeRoomVisitService.Site site = TributeRoomVisitService.planSite(overworld, 0);
        if (site == null) return;
        CompoundTag fingerprint = fingerprint(overworld, site.window());
        TributeRoomStore.supplyIo(TributeSiteCache::read).whenComplete((cached, error) -> server.execute(() -> {
            if (server.overworld() != overworld) return;
            if (cached != null && fingerprint.equals(cached.getCompound("fingerprint"))) {
                inject(overworld, cached.getList("chunks", Tag.TAG_COMPOUND));
            } else {
                LOGGER.info("[DungeonTrain] tribute site: {} — generating it in the background to keep",
                    cached == null ? "none kept yet" : "the kept one is for another world shape");
                startWarm(overworld, site.window(), fingerprint);
            }
        }));
    }

    /** What makes a kept site fit a world: the chunk format, the build height and where the site's window falls. */
    static CompoundTag fingerprint(ServerLevel level, BoundingBox window) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("format", FORMAT);
        tag.putInt("dataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        tag.putInt("minY", level.getMinBuildHeight());
        tag.putInt("height", level.getHeight());
        tag.putIntArray("window", new int[] {window.minX(), window.minY(), window.minZ(), window.maxX(), window.maxY(), window.maxZ()});
        return tag;
    }

    /** Every chunk a visit's stamp waits on, for {@code window}: the window's and one ring. */
    static List<ChunkPos> chunksOf(BoundingBox window) {
        List<ChunkPos> out = new ArrayList<>();
        for (int cx = TributeRoomVisitTickets.minWaitX(window); cx <= TributeRoomVisitTickets.maxWaitX(window); cx++) {
            for (int cz = TributeRoomVisitTickets.minWaitZ(window); cz <= TributeRoomVisitTickets.maxWaitZ(window); cz++) {
                out.add(new ChunkPos(cx, cz));
            }
        }
        return out;
    }

    /** Write each kept chunk into the world's storage where the world has none of its own. */
    private static void inject(ServerLevel level, ListTag chunks) {
        int[] written = {0};
        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            CompoundTag chunk = chunks.getCompound(i);
            ChunkPos pos = new ChunkPos(chunk.getInt("xPos"), chunk.getInt("zPos"));
            level.getChunkSource().chunkMap.read(pos).whenComplete((existing, error) -> level.getServer().execute(() -> {
                if (error != null || existing == null || existing.isPresent()) return;
                if (level.getChunkSource().getChunkNow(pos.x, pos.z) != null) return;
                level.getChunkSource().chunkMap.write(pos, chunk.copy());
                if (++written[0] == 1) {
                    LOGGER.info("[DungeonTrain] tribute site: writing the kept site's chunks into this world");
                }
            }));
        }
    }

    private static void startWarm(ServerLevel level, BoundingBox window, CompoundTag fingerprint) {
        TributeRoomVisitTickets.holdRoom(level, window, WARM_ID);
        warmTicketHeld = true;
        warm = new Warm(window, fingerprint, new ArrayList<>(chunksOf(window)), new ListTag(), System.nanoTime());
    }

    /**
     * Server tick: once the warm-up's chunks are all in and no visit is using slot 0 (so they hold no
     * room), serialize a few a tick; when all are captured, write the file and let the chunks go.
     */
    public static void tick(MinecraftServer server) {
        Warm w = warm;
        if (w == null) return;
        ServerLevel level = server.overworld();
        if (w.captured().isEmpty() && !TributeRoomVisitTickets.ready(level, w.window())) return;
        if (!TributeRoomVisitService.slotFree(0)) return;
        for (int i = 0; i < CHUNKS_PER_TICK && !w.pending().isEmpty(); i++) {
            ChunkPos pos = w.pending().remove(w.pending().size() - 1);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) {
                // Unloaded under us: start the capture again rather than keep a site with a hole in it.
                LOGGER.info("[DungeonTrain] tribute site: chunk {} left before it was kept — starting over", pos);
                warm = new Warm(w.window(), w.fingerprint(), new ArrayList<>(chunksOf(w.window())), new ListTag(), w.startedNs());
                return;
            }
            w.captured().add(portable(ChunkSerializer.write(level, chunk)));
        }
        if (!w.pending().isEmpty()) return;
        warm = null;
        releaseWarmTicket(level, w.window());
        CompoundTag file = new CompoundTag();
        file.put("fingerprint", w.fingerprint());
        file.put("chunks", w.captured());
        long tookMs = (System.nanoTime() - w.startedNs()) / 1_000_000L;
        TributeRoomStore.runIo(() -> write(file)).whenComplete((v, error) -> LOGGER.info(
            "[DungeonTrain] tribute site: kept {} chunk(s) of the visit site after {} ms{}", w.captured().size(), tookMs,
            error == null ? "" : " — but the file did not write: " + error));
    }

    /** {@code chunk} as saved, less what belongs to the world it was saved in. */
    static CompoundTag portable(CompoundTag chunk) {
        CompoundTag out = chunk.copy();
        for (String key : WORLD_SPECIFIC) out.remove(key);
        out.putLong("InhabitedTime", 0L);
        out.putLong("LastUpdate", 0L);
        return out;
    }

    /** Server stopped: drop the warm-up (its ticket went with the world). */
    public static void clear() {
        warm = null;
        warmTicketHeld = false;
    }

    private static void releaseWarmTicket(ServerLevel level, BoundingBox window) {
        if (!warmTicketHeld) return;
        warmTicketHeld = false;
        TributeRoomVisitTickets.releaseRoom(level, window, WARM_ID);
    }

    @Nullable
    private static CompoundTag read() {
        Path file = file();
        if (!Files.isRegularFile(file)) return null;
        try {
            return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] tribute site: could not read {}: {}", file, e.toString());
            return null;
        }
    }

    private static void write(CompoundTag tag) {
        Path file = file();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(tag, tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
