package games.brennan.dungeontrain.player;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.SimpleContainer;
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
 * Per-player Ender Chest contents that persist <em>outside</em> any individual
 * world save. Lives at
 * {@code <minecraft>/config/dungeontrain-enderchest/<uuid>.dat}.
 *
 * <p>Dungeon Train creates a fresh MC world on each death, which resets the
 * vanilla per-world {@code playerdata/<uuid>.dat} (and thus the Ender Chest).
 * This store snapshots the Ender Chest on every player logout and restores it
 * on every player login, bridging the gap across new-world transitions exactly
 * as {@link GlobalPlayerStats} does for cumulative stats.</p>
 *
 * <p>Disk I/O strategy: compressed binary NBT (same format used by
 * {@link net.minecraft.nbt.NbtIo} throughout the codebase). Items are
 * serialised with the player's live {@link HolderLookup.Provider} so
 * DataComponents referencing registry entries survive round-trips, matching
 * the approach used in {@link PendingInventory}.</p>
 *
 * <p>Cache: an in-memory {@link ConcurrentHashMap} backed by lazy disk loads.
 * A {@code null} value means nothing has ever been stored for that UUID;
 * an empty {@link CompoundTag} means the chest was saved while empty.</p>
 */
public final class GlobalEnderChestStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIR_NAME = "dungeontrain-enderchest";

    /** In-memory cache: UUID → serialised chest tag (null = never saved). */
    private static final Map<UUID, CompoundTag> CACHE = new ConcurrentHashMap<>();

    private GlobalEnderChestStore() {}

    public static Path file(UUID uuid) {
        return FMLPaths.CONFIGDIR.get().resolve(DIR_NAME).resolve(uuid + ".dat");
    }

    /**
     * Snapshot {@code player}'s Ender Chest into the cache and write it to
     * disk. Called on player logout and during server shutdown.
     */
    public static void save(ServerPlayer player) {
        HolderLookup.Provider registries = player.registryAccess();
        SimpleContainer enderChest = player.getEnderChestInventory();
        ListTag itemList = new ListTag();
        for (int i = 0; i < enderChest.getContainerSize(); i++) {
            ItemStack stack = enderChest.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = (CompoundTag) stack.save(registries, new CompoundTag());
                slotTag.putByte("Slot", (byte) i);
                itemList.add(slotTag);
            }
        }
        CompoundTag root = new CompoundTag();
        root.put("Items", itemList);
        UUID uuid = player.getUUID();
        CACHE.put(uuid, root);
        LOGGER.debug("[DungeonTrain] EnderChestStore: saved {} item stack(s) for {}",
                itemList.size(), player.getName().getString());
    }

    /**
     * Apply the stored Ender Chest contents onto {@code player}. Called on
     * every player login — no-op if nothing has been stored yet for this UUID.
     */
    public static void restore(ServerPlayer player) {
        UUID uuid = player.getUUID();
        CompoundTag root = CACHE.computeIfAbsent(uuid, GlobalEnderChestStore::loadFromDisk);
        if (root == null) return;
        try {
            HolderLookup.Provider registries = player.registryAccess();
            SimpleContainer enderChest = player.getEnderChestInventory();
            enderChest.clearContent();
            ListTag itemList = root.getList("Items", 10); // 10 = CompoundTag tag id
            int applied = 0;
            for (int i = 0; i < itemList.size(); i++) {
                CompoundTag slotTag = itemList.getCompound(i);
                int slot = slotTag.getByte("Slot") & 0xFF;
                if (slot < enderChest.getContainerSize()) {
                    ItemStack stack = ItemStack.parse(registries, slotTag).orElse(ItemStack.EMPTY);
                    if (!stack.isEmpty()) {
                        enderChest.setItem(slot, stack);
                        applied++;
                    }
                }
            }
            player.inventoryMenu.broadcastChanges();
            if (applied > 0) {
                LOGGER.info("[DungeonTrain] EnderChestStore: restored {} item stack(s) for {}",
                        applied, player.getName().getString());
            }
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] EnderChestStore: restore failed for {} — Ender Chest stays as loaded",
                    player.getName().getString(), e);
        }
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
