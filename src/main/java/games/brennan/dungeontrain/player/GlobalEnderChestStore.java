package games.brennan.dungeontrain.player;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.level.GameType;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player, per-game-mode Ender Chest contents that persist <em>outside</em>
 * any individual world save. Lives at
 * {@code <minecraft>/config/dungeontrain-enderchest/<uuid>.dat}.
 *
 * <p>Dungeon Train creates a fresh MC world on each death, which resets the
 * vanilla per-world {@code playerdata/<uuid>.dat} and thus the Ender Chest.
 * This store snapshots the Ender Chest on every player logout and restores it
 * on every login, bridging the gap across new-world transitions exactly as
 * {@link GlobalPlayerStats} does for cumulative stats.</p>
 *
 * <p>Separate inventories are maintained per {@link GameType}: survival,
 * creative, adventure, and spectator each have their own 27-slot snapshot.
 * The on-disk format is a single root {@link CompoundTag} keyed by the
 * game-mode serialized name ({@code "survival"}, {@code "creative"}, …), so
 * all four slots share one file. Switching game modes mid-session triggers an
 * immediate swap via {@link #swapGameMode}, so the live
 * {@link PlayerEnderChestContainer} always reflects the current mode.</p>
 *
 * <p>Disk I/O: compressed binary NBT via {@link NbtIo}. Serialisation and
 * deserialisation delegate to {@link PlayerEnderChestContainer#createTag} and
 * {@link PlayerEnderChestContainer#fromTag}, which are the same paths vanilla
 * uses for player-data saves.</p>
 */
public final class GlobalEnderChestStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIR_NAME = "dungeontrain-enderchest";

    /** In-memory cache: UUID → root tag (null = nothing ever saved). */
    private static final Map<UUID, CompoundTag> CACHE = new ConcurrentHashMap<>();

    private GlobalEnderChestStore() {}

    public static Path file(UUID uuid) {
        return FMLPaths.CONFIGDIR.get().resolve(DIR_NAME).resolve(uuid + ".dat");
    }

    // ---- Public API ----

    /**
     * Snapshot {@code player}'s Ender Chest for their current game mode into
     * the cache and write it to disk. Other game-mode slots are preserved.
     * Called on player logout.
     */
    public static void save(ServerPlayer player) {
        String mode = currentMode(player);
        ListTag items = player.getEnderChestInventory().createTag(player.registryAccess());
        updateCache(player.getUUID(), mode, items);
        LOGGER.debug("[DungeonTrain] EnderChestStore: saved {} item stack(s) for {} ({})",
                items.size(), player.getName().getString(), mode);
    }

    /**
     * Apply the stored Ender Chest contents for the player's current game mode
     * onto {@code player}. No-op if nothing has been stored for this UUID and
     * game mode. Called on every player login.
     */
    public static void restore(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String mode = currentMode(player);
        CompoundTag root = CACHE.computeIfAbsent(uuid, GlobalEnderChestStore::loadFromDisk);
        if (root == null || !root.contains(mode, Tag.TAG_LIST)) return;
        ListTag items = root.getList(mode, Tag.TAG_COMPOUND);
        player.getEnderChestInventory().fromTag(items, player.registryAccess());
        if (!items.isEmpty()) {
            LOGGER.info("[DungeonTrain] EnderChestStore: restored {} item stack(s) for {} ({})",
                    items.size(), player.getName().getString(), mode);
        }
    }

    /**
     * Save the current game mode's Ender Chest then load the new game mode's
     * Ender Chest into the player's live {@link PlayerEnderChestContainer}.
     * Called from {@link net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangeGameModeEvent}.
     * The old mode is still active at call time; {@code newMode} is what the
     * player is switching to.
     */
    public static void swapGameMode(ServerPlayer player, GameType newMode) {
        UUID uuid = player.getUUID();
        String oldModeKey = currentMode(player);
        String newModeKey = newMode.getSerializedName();

        // Persist the current chest under the old mode key.
        ListTag oldItems = player.getEnderChestInventory().createTag(player.registryAccess());
        CompoundTag root = CACHE.compute(uuid, (k, existing) -> {
            CompoundTag r = existing != null ? existing : loadFromDisk(k);
            if (r == null) r = new CompoundTag();
            r.put(oldModeKey, oldItems);
            return r;
        });

        // Load the new mode's chest (empty if nothing stored yet).
        PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
        if (root.contains(newModeKey, Tag.TAG_LIST)) {
            ListTag newItems = root.getList(newModeKey, Tag.TAG_COMPOUND);
            enderChest.fromTag(newItems, player.registryAccess());
        } else {
            enderChest.clearContent();
        }

        LOGGER.info("[DungeonTrain] EnderChestStore: swapped ender chest {} → {} for {}",
                oldModeKey, newModeKey, player.getName().getString());
    }

    /** Write the cached entry for {@code uuid} to disk. No-op if not in cache. */
    public static void flush(UUID uuid) {
        CompoundTag tag = CACHE.get(uuid);
        if (tag == null) return;
        saveToDisk(uuid, tag);
    }

    /** Drop the cached entry after flushing. Used on logout. */
    public static void evict(UUID uuid) {
        CACHE.remove(uuid);
    }

    /** Flush every cached player. Called on server stop. */
    public static void flushAll() {
        Map<UUID, CompoundTag> snapshot = new HashMap<>(CACHE);
        for (var entry : snapshot.entrySet()) {
            saveToDisk(entry.getKey(), entry.getValue());
        }
    }

    // ---- Internals ----

    private static String currentMode(ServerPlayer player) {
        return player.gameMode.getGameModeForPlayer().getSerializedName();
    }

    private static void updateCache(UUID uuid, String modeKey, ListTag items) {
        CACHE.compute(uuid, (k, existing) -> {
            CompoundTag root = existing != null ? existing : loadFromDisk(k);
            if (root == null) root = new CompoundTag();
            root.put(modeKey, items);
            return root;
        });
    }

    private static CompoundTag loadFromDisk(UUID uuid) {
        Path path = file(uuid);
        if (!Files.isRegularFile(path)) return null;
        try {
            return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] EnderChestStore: I/O error reading {}: {}",
                    path, e.getMessage());
            return null;
        }
    }

    private static synchronized void saveToDisk(UUID uuid, CompoundTag tag) {
        Path path = file(uuid);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] EnderChestStore: failed to create dir {}: {}",
                    path.getParent(), e.getMessage());
            return;
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            NbtIo.writeCompressed(tag, tmp);
            Files.move(tmp, path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.error("[DungeonTrain] EnderChestStore: rename {} -> {} failed: {}",
                        tmp, path, e2.getMessage());
            }
        }
    }
}
