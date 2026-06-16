package games.brennan.dungeontrain.player;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
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
 * Per-player, per-game-mode Ender Chest contents that persist <em>outside</em>
 * any individual world save. Lives at
 * {@code <minecraft>/config/dungeontrain-enderchest/<uuid>.dat}.
 *
 * <p>Dungeon Train creates a fresh MC world on each death, which resets the
 * vanilla per-world {@code playerdata/<uuid>.dat} (and thus the Ender Chest).
 * This store snapshots the Ender Chest on every player logout and restores it
 * on every login, bridging the gap across new-world transitions exactly as
 * {@link GlobalPlayerStats} does for cumulative stats.</p>
 *
 * <p>Separate inventories are maintained per {@link GameType}: survival,
 * creative, adventure, and spectator each have their own 27-slot snapshot.
 * The on-disk format is a single root {@link CompoundTag} keyed by the
 * game-mode serialized name ({@code "survival"}, {@code "creative"}, …), so
 * all four slots share one file and switching modes never cross-contaminates
 * inventories.</p>
 *
 * <p>Disk I/O: compressed binary NBT via {@link NbtIo}. Items are serialised
 * with the player's live {@link HolderLookup.Provider} so DataComponents
 * referencing registry entries survive round-trips, matching {@link PendingInventory}.</p>
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

    /**
     * Snapshot {@code player}'s Ender Chest for their current game mode into
     * the cache and write it to disk. Other game-mode slots in the same file
     * are preserved.
     */
    public static void save(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String mode = player.gameMode.getGameModeForPlayer().getSerializedName();

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
        CompoundTag modeTag = new CompoundTag();
        modeTag.put("Items", itemList);

        CACHE.compute(uuid, (k, existing) -> {
            CompoundTag root = existing != null ? existing : loadFromDisk(k);
            if (root == null) root = new CompoundTag();
            root.put(mode, modeTag);
            return root;
        });

        LOGGER.debug("[DungeonTrain] EnderChestStore: saved {} item stack(s) for {} ({})",
                itemList.size(), player.getName().getString(), mode);
    }

    /**
     * Apply the stored Ender Chest contents for the player's current game mode
     * onto {@code player}. No-op if nothing has been stored for this UUID and
     * game mode. Called on every player login.
     */
    public static void restore(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String mode = player.gameMode.getGameModeForPlayer().getSerializedName();
        CompoundTag root = CACHE.computeIfAbsent(uuid, GlobalEnderChestStore::loadFromDisk);
        if (root == null || !root.contains(mode)) return;
        try {
            HolderLookup.Provider registries = player.registryAccess();
            SimpleContainer enderChest = player.getEnderChestInventory();
            enderChest.clearContent();
            ListTag itemList = root.getCompound(mode).getList("Items", 10);
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
                LOGGER.info("[DungeonTrain] EnderChestStore: restored {} item stack(s) for {} ({})",
                        applied, player.getName().getString(), mode);
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
