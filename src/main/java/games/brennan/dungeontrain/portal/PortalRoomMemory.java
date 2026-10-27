package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.compat.vista.TvBlocks;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.player.DifficultyPartition;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.LeaseSnapshots;
import games.brennan.dungeontrain.world.DifficultyLootClear;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What a portal room looked like when a player last left it, kept so the next stamp of that pair lays
 * the room as they left it rather than re-rolling its template.
 *
 * <h2>Why</h2>
 * <p>A pair's twin is erased and stamped afresh whenever the train drifts too far from it, whenever
 * another pair evicts it, and after every restart (the structure maps are in-memory). Every one of
 * those stamps was a pure function of seed and pair key, so a mined block came back and a looted chest
 * refilled — a room holding one diamond block held an unlimited number of them. Only the drifting
 * shared rooms carried their blocks across a relocation, and those are one pair in fifteen.</p>
 *
 * <h2>What is kept</h2>
 * <p>Only pairs a player has stood inside ({@link #markTouched}) — anything a player can take, they
 * take from inside the room, and an untouched room is identical to its template anyway. Their room box
 * is captured with {@link CarriageBlockSnapshot#captureLevel} (blocks, block-entity NBT including chest
 * contents, entities) just before it is erased, and handed back on the pair's next fresh stamp as a
 * {@link PortalRoomBlob#live live blob}. A snapshot whose room name or box no longer matches what the
 * pair plans (the template was renamed or resized since) is dropped and the template is stamped.</p>
 *
 * <h2>Where</h2>
 * <p>{@code <world>/dungeontrain/portal-room-memory/<dim>/<pairKey>.nbt}, compressed, written at capture
 * time so a restart keeps it. Pair keys are global carriage indices that hold for the life of the world
 * (as {@link PortalRegistry}'s per-pair stage does), so the store is never cleared wholesale; it is
 * bounded instead to the {@link #MAX_REMEMBERED} most recently written rooms — a room being farmed is
 * always a recent one.</p>
 *
 * <h2>Copies</h2>
 * <p>A room that tiles ({@link PortalRoomMode#tiles}) stands as a grid of copies around the base room,
 * and each copy is stamped from the template and erased again as it slides out of the window — or as
 * the room drains once its last player has left. Only the base box went into the memory above, so a
 * player who mined a copy, walked out and came back found it whole again. A copy a player has stood in
 * ({@link #markTileTouched}) is therefore captured when {@code PortalRoomTiler} erases it and laid back
 * the next time that tile is stamped. Tiles are counted from the base room, so a copy keeps its identity
 * across a relocation. They live under {@code tiles/} beside the rooms with a cap of their own
 * ({@link #MAX_REMEMBERED_TILES}), so a wide walk through copies never pushes a base room out. A tile
 * nobody has reached is still a fresh template copy: an endless room is endless by design.</p>
 */
public final class PortalRoomMemory {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How many rooms a world remembers before the least recently written are forgotten. */
    public static final int MAX_REMEMBERED = 256;

    /** How many room copies a world remembers, separately from {@link #MAX_REMEMBERED}. */
    public static final int MAX_REMEMBERED_TILES = 1024;

    private static final String SUBDIR = "dungeontrain/portal-room-memory";
    private static final String TILES_SUBDIR = "tiles";
    private static final String EXT = ".nbt";
    private static final String TAG_ROOM = "room";
    private static final String TAG_SNAPSHOT = "snap";
    private static final String TAG_DIFFICULTY = "difficulty";

    /** Where a touched pair's room stands, so a shutdown can capture every one still standing. */
    public record Touch(ServerLevel level, CarriageDims dims) {}

    /**
     * A remembered room: the room it was rolled as, its captured snapshot, and the difficulty profile it
     * was captured under ({@code ""} for rooms remembered before that was recorded).
     */
    public record Entry(String roomName, CompoundTag snapshot, String difficulty) {
        public Entry(String roomName, CompoundTag snapshot) {
            this(roomName, snapshot, "");
        }
    }

    /** Pairs a player has stood inside this session — the rooms worth capturing before an erase. */
    private static final Map<Integer, Touch> TOUCHED = new HashMap<>();

    /** Copies (never {@link PortalRoomTiling.Tile#BASE}) a player has stood in, per pair, this session. */
    private static final Map<Integer, Set<PortalRoomTiling.Tile>> TOUCHED_TILES = new HashMap<>();

    private PortalRoomMemory() {}

    // ---- touched ------------------------------------------------------------

    /** A player is standing inside pair {@code pairKey}'s structure. */
    public static void markTouched(ServerLevel level, int pairKey, CarriageDims dims) {
        TOUCHED.put(pairKey, new Touch(level, dims));
    }

    public static boolean isTouched(int pairKey) {
        return TOUCHED.containsKey(pairKey);
    }

    /** Every pair touched this session, for the shutdown capture. A copy — callers may not mutate it. */
    public static Map<Integer, Touch> touched() {
        return Map.copyOf(TOUCHED);
    }

    /**
     * A player is standing in copy {@code tile} of pair {@code pairKey}'s room. The base tile is the
     * pair's own memory ({@link #markTouched}) and is ignored here.
     */
    public static void markTileTouched(int pairKey, PortalRoomTiling.Tile tile) {
        if (PortalRoomTiling.Tile.BASE.equals(tile)) return;
        TOUCHED_TILES.computeIfAbsent(pairKey, k -> new HashSet<>()).add(tile);
    }

    public static boolean isTileTouched(int pairKey, PortalRoomTiling.Tile tile) {
        Set<PortalRoomTiling.Tile> tiles = TOUCHED_TILES.get(pairKey);
        return tiles != null && tiles.contains(tile);
    }

    /** The server stopped: the next world's pair keys mean different rooms. The files stay. */
    public static void clearSession() {
        TOUCHED.clear();
        TOUCHED_TILES.clear();
    }

    // ---- capture / remember / recall -----------------------------------------

    /**
     * Capture the room box at {@code roomOrigin} as it stands. Null when the capture fails — the caller
     * then falls back to the template, which is what happened before this existed.
     */
    public static CompoundTag capture(ServerLevel level, BlockPos roomOrigin, Vec3i size) {
        try {
            return CarriageBlockSnapshot.captureLevel(level, roomOrigin, size, level.registryAccess(),
                DungeonTrainConfig.getSharedCarriageMaxEntities()).tag();
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not capture portal room at {} ({}) — it will be stamped from "
                + "its template next time: {}", roomOrigin, size, t.toString());
            return null;
        }
    }

    /** Remember {@code snapshot} as pair {@code pairKey}'s room, on disk, then trim the store. */
    public static void remember(ServerLevel level, int pairKey, String roomName, CompoundTag snapshot) {
        Path file = fileFor(level, pairKey);
        if (write(file, new Entry(roomName, snapshot, DifficultyPartition.keyFor(level)), "portal pair " + pairKey + "'s room")) {
            prune(file.getParent(), MAX_REMEMBERED);
        }
    }

    /**
     * Pair {@code pairKey}'s remembered room, when there is one for {@code roomName} at exactly
     * {@code size}; null otherwise. A stale entry (another room, another size) is deleted on the way.
     */
    public static CompoundTag recall(ServerLevel level, int pairKey, String roomName, Vec3i size) {
        return read(level, fileFor(level, pairKey), roomName, size, "portal pair " + pairKey + "'s room");
    }

    /** Drop pair {@code pairKey}'s remembered room, if any. */
    public static void forget(ServerLevel level, int pairKey) {
        delete(fileFor(level, pairKey), "portal pair " + pairKey + "'s room");
    }

    /** Remember {@code snapshot} as copy {@code tile} of pair {@code pairKey}'s room, then trim the copies. */
    public static void rememberTile(ServerLevel level, int pairKey, PortalRoomTiling.Tile tile,
                                    String roomName, CompoundTag snapshot) {
        Path file = tileFileFor(level, pairKey, tile);
        if (write(file, new Entry(roomName, snapshot, DifficultyPartition.keyFor(level)), describeTile(pairKey, tile))) {
            prune(file.getParent(), MAX_REMEMBERED_TILES);
        }
    }

    /** As {@link #recall}, for copy {@code tile} of pair {@code pairKey}'s room. */
    public static CompoundTag recallTile(ServerLevel level, int pairKey, PortalRoomTiling.Tile tile,
                                         String roomName, Vec3i size) {
        return read(level, tileFileFor(level, pairKey, tile), roomName, size, describeTile(pairKey, tile));
    }

    private static String describeTile(int pairKey, PortalRoomTiling.Tile tile) {
        return "portal pair " + pairKey + "'s copy " + tile.x() + "," + tile.z();
    }

    private static boolean write(Path file, Entry entry, String what) {
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(encode(entry), file);
            return true;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not remember {} at {}: {}", what, file, e.toString());
            return false;
        }
    }

    private static CompoundTag read(ServerLevel level, Path file, String roomName, Vec3i size, String what) {
        if (!Files.isRegularFile(file)) return null;
        Entry entry;
        try {
            entry = decode(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not read {} remembered at {} — stamping its template: {}",
                what, file, e.toString());
            return null;
        }
        if (!fits(entry, roomName, size)) {
            LOGGER.info("[DungeonTrain] {} as remembered no longer fits ('{}' vs '{}' at {}) — forgetting it",
                what, entry == null ? "?" : entry.roomName(), roomName, size);
            delete(file, what);
            return null;
        }
        return forCurrentDifficulty(level, entry, what);
    }

    /**
     * The snapshot to lay, minus the loot it held if it was captured under another difficulty: a room
     * looted on one difficulty and stocked by the player must not hand that stock to the next
     * ({@code DifficultyWorldLootEvents}). Untouched rolled containers keep their loot.
     */
    private static CompoundTag forCurrentDifficulty(ServerLevel level, Entry entry, String what) {
        String current = DifficultyPartition.keyFor(level);
        if (!DungeonTrainConfig.getDifficultyIsolatedStash()
                || !DifficultyLootClear.isStale(entry.difficulty(), current)) {
            return entry.snapshot();
        }
        CompoundTag snapshot = entry.snapshot().copy();
        HolderGetter<Block> blocks = level.holderLookup(Registries.BLOCK);
        DifficultyLootClear.scrubSnapshot(snapshot, level.registryAccess(),
            stateTag -> TvBlocks.isTelevision(NbtUtils.readBlockState(blocks, stateTag)));
        LOGGER.info("[DungeonTrain] {} was remembered on {}; its loot was cleared for {}",
            what, entry.difficulty(), current);
        return snapshot;
    }

    private static void delete(Path file, String what) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not forget {}: {}", what, e.toString());
        }
    }

    // ---- pure helpers (unit-tested) -----------------------------------------

    /** Whether {@code entry} can stand in for room {@code roomName} at box {@code size}. */
    static boolean fits(Entry entry, String roomName, Vec3i size) {
        return entry != null
            && entry.roomName().equals(roomName)
            && LeaseSnapshots.matchesDims(entry.snapshot(), size.getX(), size.getY(), size.getZ());
    }

    static CompoundTag encode(Entry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_ROOM, entry.roomName());
        tag.put(TAG_SNAPSHOT, entry.snapshot());
        if (!entry.difficulty().isEmpty()) tag.putString(TAG_DIFFICULTY, entry.difficulty());
        return tag;
    }

    /** Null for a tag that does not hold an entry. */
    static Entry decode(CompoundTag tag) {
        if (tag == null || !tag.contains(TAG_ROOM) || !tag.contains(TAG_SNAPSHOT)) return null;
        return new Entry(tag.getString(TAG_ROOM), tag.getCompound(TAG_SNAPSHOT), tag.getString(TAG_DIFFICULTY));
    }

    /** The files to delete so at most {@code keep} remain: the oldest by write time. */
    static List<Path> toPrune(Map<Path, Long> writtenAt, int keep) {
        if (writtenAt.size() <= keep) return List.of();
        List<Path> oldestFirst = new ArrayList<>(writtenAt.keySet());
        oldestFirst.sort(Comparator.comparingLong((Path p) -> writtenAt.get(p)).thenComparing(Path::toString));
        return List.copyOf(oldestFirst.subList(0, writtenAt.size() - keep));
    }

    /** The file a copy is kept in, inside its dimension's {@code tiles/} folder. */
    static String tileFileName(int pairKey, PortalRoomTiling.Tile tile) {
        return pairKey + "_" + tile.x() + "_" + tile.z() + EXT;
    }

    // ---- disk ---------------------------------------------------------------

    /** Keeps the newest {@code keep} files directly in {@code dir}; subfolders are not counted. */
    private static void prune(Path dir, int keep) {
        Map<Path, Long> writtenAt = new HashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(EXT)).forEach(p -> {
                try {
                    writtenAt.put(p, Files.getLastModifiedTime(p).toMillis());
                } catch (IOException ignored) {
                    // Vanished between the listing and the stat — nothing to prune.
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not list remembered portal rooms in {}: {}", dir, e.toString());
            return;
        }
        for (Path p : toPrune(writtenAt, keep)) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Could not prune remembered portal room {}: {}", p, e.toString());
            }
        }
    }

    private static Path fileFor(ServerLevel level, int pairKey) {
        return dimDir(level).resolve(pairKey + EXT);
    }

    private static Path tileFileFor(ServerLevel level, int pairKey, PortalRoomTiling.Tile tile) {
        return dimDir(level).resolve(TILES_SUBDIR).resolve(tileFileName(pairKey, tile));
    }

    private static Path dimDir(ServerLevel level) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        ResourceLocation dim = level.dimension().location();
        // Colons are reserved on Windows.
        String dimSeg = dim.getNamespace() + "__" + dim.getPath();
        return worldRoot.resolve(SUBDIR).resolve(dimSeg);
    }
}
