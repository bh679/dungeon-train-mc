package games.brennan.dungeontrain.player;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player, per-difficulty inventory + experience — the locker that makes a difficulty change a
 * <em>profile</em> change. Stored at {@code <config>/dungeontrain/loadouts/<uuid>.dat}, one file per
 * player with the {@link DifficultyPartition} key as the top-level tag name.
 *
 * <p>Deliberately modelled on EnderChestPersistence's {@code EnderChestStore}: same cache +
 * save-old-then-load-new swap, same "one file per player, one tag per slot" layout. The two halves of a
 * difficulty profile — stash and loadout — should behave identically, and the ECP shape is already
 * proven against the thing that makes this hard, namely that Dungeon Train throws its world away on
 * every death. Per-install storage survives that; a {@code SavedData} would not.</p>
 *
 * <p><b>Scope.</b> This is only touched when the difficulty actually changes
 * ({@code DifficultyIsolationEvents}). It is not a login/logout hook: within one difficulty the
 * inventory already persists the normal way (the world save, or {@link PendingInventory} across the
 * death&rarr;new-world boundary). Adding a login path would fight both.</p>
 *
 * <p>Server-thread only, and every operation is best-effort: an I/O or parse failure is logged and the
 * player keeps whatever they are holding. Losing a locker read must never cost someone their inventory.</p>
 */
public final class LoadoutStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String DIR = "dungeontrain";
    private static final String SUBDIR = "loadouts";

    /** Inside one partition's tag: the saved slots, and the three vanilla XP fields. */
    private static final String TAG_ITEMS = "Items";
    private static final String TAG_SLOT = "Slot";
    private static final String TAG_XP_LEVEL = "XpLevel";
    private static final String TAG_XP_PROGRESS = "XpProgress";
    private static final String TAG_XP_TOTAL = "XpTotal";

    /** UUID → root tag (all of that player's partitions). Written through to disk on every change. */
    private static final Map<UUID, CompoundTag> CACHE = new ConcurrentHashMap<>();

    private LoadoutStore() {}

    /** The file holding every partition's loadout for {@code uuid}. */
    public static Path file(UUID uuid) {
        // Moved out of config/ with the rest of the player's data — a modpack update replaces
        // that folder. See PlayerDataPaths.
        return PlayerDataPaths.locate(
            PlayerDataPaths.LOADOUTS, DIR + "/" + SUBDIR, uuid + ".dat").read();
    }

    /**
     * Store the player's current inventory + XP under {@code fromPartition}, then load
     * {@code toPartition}'s — clearing the inventory when that partition has nothing saved (a difficulty
     * you have never played starts empty, which is the whole point).
     *
     * <p>Order matters and is not negotiable: the outgoing loadout is captured and written to disk
     * <em>before</em> anything is cleared, so a failure part-way through can't lose it.</p>
     */
    public static void swap(ServerPlayer player, String fromPartition, String toPartition) {
        if (fromPartition.equals(toPartition)) {
            return;
        }
        UUID uuid = player.getUUID();
        CompoundTag saved = capture(player);
        CompoundTag root = CACHE.compute(uuid, (k, existing) -> {
            CompoundTag r = existing != null ? existing : loadFromDisk(k);
            if (r == null) {
                r = new CompoundTag();
            }
            r.put(fromPartition, saved);
            return r;
        });
        saveToDisk(uuid, root);

        if (root.contains(toPartition, Tag.TAG_COMPOUND)) {
            apply(player, root.getCompound(toPartition));
        } else {
            clear(player);
        }
        LOGGER.info("[DungeonTrain] loadout swapped {} → {} for {}",
            fromPartition, toPartition, player.getName().getString());
    }

    /**
     * Whether {@code uuid} has anything saved under {@code partition} — i.e. whether switching to
     * that difficulty would restore a loadout or start them from nothing. Read-only as far as the
     * stored data goes; it warms the same cache entry {@link #swap} would.
     */
    public static boolean has(UUID uuid, String partition) {
        CompoundTag root = CACHE.computeIfAbsent(uuid, k -> {
            CompoundTag loaded = loadFromDisk(k);
            return loaded != null ? loaded : new CompoundTag();
        });
        return root.contains(partition, Tag.TAG_COMPOUND);
    }

    /** Snapshot {@code player}'s inventory + XP as one partition's tag. */
    private static CompoundTag capture(ServerPlayer player) {
        HolderLookup.Provider registries = player.registryAccess();
        Inventory inv = player.getInventory();
        ListTag items = new ListTag();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            // Registry-serialized, like PendingInventory: a stack's components/enchantments are holders
            // into the dynamic registries, and this file outlives the world those came from.
            CompoundTag entry = (CompoundTag) stack.save(registries, new CompoundTag());
            entry.putInt(TAG_SLOT, i);
            items.add(entry);
        }
        CompoundTag out = new CompoundTag();
        out.put(TAG_ITEMS, items);
        out.putInt(TAG_XP_LEVEL, player.experienceLevel);
        out.putFloat(TAG_XP_PROGRESS, player.experienceProgress);
        out.putInt(TAG_XP_TOTAL, player.totalExperience);
        return out;
    }

    /** Replace {@code player}'s inventory + XP with the loadout in {@code tag}. */
    private static void apply(ServerPlayer player, CompoundTag tag) {
        try {
            HolderLookup.Provider registries = player.registryAccess();
            Inventory inv = player.getInventory();
            inv.clearContent();
            ListTag items = tag.getList(TAG_ITEMS, Tag.TAG_COMPOUND);
            int applied = 0;
            for (int i = 0; i < items.size(); i++) {
                CompoundTag entry = items.getCompound(i);
                int slot = entry.getInt(TAG_SLOT);
                if (slot < 0 || slot >= inv.getContainerSize()) {
                    continue;
                }
                // A single unparseable stack is skipped rather than failing the whole loadout.
                ItemStack stack = ItemStack.parse(registries, entry).orElse(ItemStack.EMPTY);
                if (!stack.isEmpty()) {
                    inv.setItem(slot, stack);
                    applied++;
                }
            }
            setXp(player, tag.getInt(TAG_XP_LEVEL), tag.getFloat(TAG_XP_PROGRESS), tag.getInt(TAG_XP_TOTAL));
            player.inventoryMenu.broadcastChanges();
            LOGGER.info("[DungeonTrain] loadout restored {} item stack(s) + level {} XP onto {}",
                applied, player.experienceLevel, player.getName().getString());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] loadout restore failed; player keeps their current inventory", e);
        }
    }

    /** Empty the player's inventory + XP — entering a difficulty they have never played. */
    private static void clear(ServerPlayer player) {
        player.getInventory().clearContent();
        setXp(player, 0, 0.0f, 0);
        player.inventoryMenu.broadcastChanges();
    }

    /** Assign the three XP fields directly, mirroring vanilla {@code ServerPlayer.restoreFrom}. */
    private static void setXp(ServerPlayer player, int level, float progress, int total) {
        player.experienceLevel = level;
        player.experienceProgress = progress;
        player.totalExperience = total;
    }

    private static CompoundTag loadFromDisk(UUID uuid) {
        Path path = file(uuid);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] I/O error reading loadout {}: {}", path, e.getMessage());
            return null;
        }
    }

    private static synchronized void saveToDisk(UUID uuid, CompoundTag tag) {
        Path path = file(uuid);
        try {
            Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(tag, path);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] I/O error writing loadout {}: {}", path, e.getMessage());
        }
    }

    /** Drop the cached entry (server stop / logout). The file on disk is already current. */
    public static void evict(UUID uuid) {
        CACHE.remove(uuid);
    }

    /** Drop every cached entry (server stop). */
    public static void clearCache() {
        CACHE.clear();
    }
}
