package games.brennan.dungeontrain.perks;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Each player's perk unlocks, one set per difficulty profile, kept outside the world save because
 * Dungeon Train throws its world away on every death. Stored at
 * {@code <gameDir>/dungeontrain/perks/<uuid>.dat}, one tag per
 * {@link games.brennan.dungeontrain.player.DifficultyPartition} key, the same layout as
 * {@link games.brennan.dungeontrain.player.LoadoutStore}.
 *
 * <p>A partition's tag holds its unlock levels, plus the active ranks and apple points parked there
 * while the player is on another difficulty (see {@link PerkProfiles#swap}).</p>
 *
 * <p>Nothing is cached: the file is tiny and only touched on a login, a purchase or a difficulty
 * change. Server thread only. A file that won't parse is moved aside, not overwritten.</p>
 */
final class PerkProfileStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String TAG_UNLOCKS = "Unlocks";
    static final String TAG_PARKED_ACTIVE = "ParkedActive";
    static final String TAG_PARKED_POINTS = "ParkedPoints";
    /** The perks each past life activated ({@link PerkLifeHistory}): at the root for real play, inside a partition for tests. */
    static final String TAG_PAST_LIVES = "PastLives";
    /** Set once the player has taken a Cosmic Apple from a chest, in any life ({@link FirstAppleBoost}). */
    static final String TAG_FIRST_APPLE_FOUND = "FirstAppleFound";

    private PerkProfileStore() {}

    /** Active ranks and apple points parked on a difficulty the player has left. */
    record Parked(Map<String, Integer> active, int points) {
        static final Parked NONE = new Parked(Map.of(), 0);
    }

    private static Path file(UUID uuid) {
        return PlayerDataPaths.dir(PlayerDataPaths.PERKS).resolve(uuid + ".dat");
    }

    /** Whether this player has any saved unlocks at all, on any difficulty. */
    static boolean exists(UUID uuid) {
        return Files.isRegularFile(file(uuid));
    }

    static Map<String, Integer> unlocks(UUID uuid, String partition) {
        return unlocksIn(read(uuid), partition);
    }

    static void setUnlockLevel(UUID uuid, String partition, String perkId, int level) {
        CompoundTag root = read(uuid);
        write(uuid, withUnlockLevel(root, partition, perkId, level));
    }

    /** Replace a partition's whole unlock set. */
    static void setUnlocks(UUID uuid, String partition, Map<String, Integer> levels) {
        CompoundTag root = read(uuid);
        write(uuid, withUnlocks(root, partition, levels));
    }

    /** Park {@code parked} under {@code from}, then take (and forget) what was parked under {@code to}. */
    static Parked swapParked(UUID uuid, String from, Parked parked, String to) {
        CompoundTag root = withParked(read(uuid), from, parked);
        Parked waiting = parkedIn(root, to);
        write(uuid, withParked(root, to, Parked.NONE));
        return waiting;
    }

    /** Past lives' perks: the global list, or {@code partition}'s own when non-null (perks test worlds). */
    static PerkLifeHistory pastLives(UUID uuid, String partition) {
        return pastLivesIn(read(uuid), partition);
    }

    /** Add one ended life to the list {@link #pastLives} reads; returns the new list. */
    static PerkLifeHistory appendLife(UUID uuid, String partition, java.util.List<String> perks) {
        CompoundTag root = read(uuid);
        PerkLifeHistory next = pastLivesIn(root, partition).withLife(perks);
        write(uuid, withPastLives(root, partition, next));
        return next;
    }

    /** True once this player has taken a Cosmic Apple from a chest, in any life. */
    static boolean firstAppleFound(UUID uuid) {
        return firstAppleFoundIn(read(uuid));
    }

    /** Record that this player has taken their first Cosmic Apple. Never cleared. */
    static void markFirstAppleFound(UUID uuid) {
        CompoundTag root = read(uuid);
        if (firstAppleFoundIn(root)) return;
        write(uuid, withFirstAppleFound(root));
    }

    // ---- tag logic (pure: each write returns a new root) ----

    static boolean firstAppleFoundIn(CompoundTag root) {
        return root.getBoolean(TAG_FIRST_APPLE_FOUND);
    }

    static CompoundTag withFirstAppleFound(CompoundTag root) {
        CompoundTag next = root.copy();
        next.putBoolean(TAG_FIRST_APPLE_FOUND, true);
        return next;
    }

    static PerkLifeHistory pastLivesIn(CompoundTag root, String partition) {
        CompoundTag holder = partition == null ? root : root.getCompound(partition);
        return PerkLifeHistory.fromTag(holder, TAG_PAST_LIVES);
    }

    static CompoundTag withPastLives(CompoundTag root, String partition, PerkLifeHistory history) {
        CompoundTag next = root.copy();
        if (partition == null) {
            next.put(TAG_PAST_LIVES, history.toTag());
        } else {
            CompoundTag tag = next.getCompound(partition);
            tag.put(TAG_PAST_LIVES, history.toTag());
            next.put(partition, tag);
        }
        return next;
    }

    static Map<String, Integer> unlocksIn(CompoundTag root, String partition) {
        return readInts(root.getCompound(partition).getCompound(TAG_UNLOCKS));
    }

    static Parked parkedIn(CompoundTag root, String partition) {
        CompoundTag tag = root.getCompound(partition);
        return new Parked(readInts(tag.getCompound(TAG_PARKED_ACTIVE)), tag.getInt(TAG_PARKED_POINTS));
    }

    static CompoundTag withUnlockLevel(CompoundTag root, String partition, String perkId, int level) {
        Map<String, Integer> levels = new LinkedHashMap<>(unlocksIn(root, partition));
        if (level > 0) levels.put(perkId, level); else levels.remove(perkId);
        return withUnlocks(root, partition, levels);
    }

    static CompoundTag withUnlocks(CompoundTag root, String partition, Map<String, Integer> levels) {
        CompoundTag next = root.copy();
        CompoundTag tag = next.getCompound(partition);
        tag.put(TAG_UNLOCKS, writeInts(levels));
        next.put(partition, tag);
        return next;
    }

    static CompoundTag withParked(CompoundTag root, String partition, Parked parked) {
        CompoundTag next = root.copy();
        CompoundTag tag = next.getCompound(partition);
        tag.put(TAG_PARKED_ACTIVE, writeInts(parked.active()));
        tag.putInt(TAG_PARKED_POINTS, parked.points());
        next.put(partition, tag);
        return next;
    }

    private static Map<String, Integer> readInts(CompoundTag tag) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String key : tag.getAllKeys()) {
            if (tag.contains(key, Tag.TAG_INT) && tag.getInt(key) > 0) out.put(key, tag.getInt(key));
        }
        return out;
    }

    private static CompoundTag writeInts(Map<String, Integer> values) {
        CompoundTag tag = new CompoundTag();
        values.forEach((key, value) -> { if (value != null && value > 0) tag.putInt(key, value); });
        return tag;
    }

    // ---- disk ----

    private static CompoundTag read(UUID uuid) {
        Path path = file(uuid);
        if (!Files.isRegularFile(path)) return new CompoundTag();
        try {
            return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] perk save {} unreadable, moving it aside: {}", path, e.toString());
            moveAside(path);
            return new CompoundTag();
        }
    }

    private static void moveAside(Path path) {
        try {
            Files.move(path, path.resolveSibling(path.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] could not move perk save {} aside: {}", path, e.toString());
        }
    }

    /** Write beside the file and move over it, so a crash mid-write can't cost the player every unlock. */
    private static void write(UUID uuid, CompoundTag root) {
        Path path = file(uuid);
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, temp);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] I/O error writing perk save {}: {}", path, e.toString());
        }
    }
}
