package games.brennan.dungeontrain.player;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transient, single-JVM holder that carries a player's inventory + experience
 * across a Dungeon Train "next world" transition when {@code keepInventory} is
 * on.
 *
 * <p>Dying in Dungeon Train ends the run; "New World" tears down the integrated
 * server and {@code createFreshLevel}s a brand-new save (see
 * {@link games.brennan.dungeontrain.client.DeathScreenLayoutHandler#launchWorld}).
 * Because the next world is a fresh save, the vanilla {@code keepInventory}
 * gamerule — which only preserves items on a same-world death&rarr;respawn — does
 * nothing across that boundary. This holder bridges the gap: {@link #capture}
 * snapshots the player on the old server thread, and {@link #restore} re-applies
 * it on the new world's first login.</p>
 *
 * <p>Lifetime mirrors {@link games.brennan.dungeontrain.client.PendingWorldChoices}:
 * set during {@code launchWorld}, consumed once on the next login, then cleared.
 * Singleplayer runs both integrated servers in one JVM, so the static survives
 * the disconnect/reconnect. On a dedicated server it is never populated, so the
 * login handler that reads it is a no-op.</p>
 *
 * <p>Items are stored as registry-serialized NBT rather than live
 * {@link ItemStack} references: the world reload rebuilds the dynamic registries,
 * so a captured enchantment/component holder from the old world could dangle.
 * Saving with the old world's registries and parsing with the new world's
 * registries resolves everything cleanly — the same round-trip the editor uses
 * in {@code ContainerContentsRoller}.</p>
 *
 * <p>Common-safe: imports no client classes, so it can be written from the
 * client {@code launchWorld} and read from the server-side login handler.</p>
 */
public final class PendingInventory {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One captured slot: its inventory index and the registry-serialized stack. */
    private record SavedSlot(int slot, CompoundTag tag) {}

    private static volatile UUID playerId;
    private static volatile List<SavedSlot> slots;
    private static volatile int xpLevel;
    private static volatile float xpProgress;
    private static volatile int xpTotal;

    private PendingInventory() {}

    /**
     * Snapshot {@code player}'s full inventory (36 main + 4 armor + 1 offhand)
     * and experience into the holder. Must run on the server thread — call from
     * inside a {@code server.execute(...)} block.
     */
    public static void capture(ServerPlayer player) {
        HolderLookup.Provider registries = player.registryAccess();
        Inventory inv = player.getInventory();
        List<SavedSlot> saved = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                saved.add(new SavedSlot(i, (CompoundTag) stack.save(registries, new CompoundTag())));
            }
        }
        playerId = player.getUUID();
        slots = saved;
        xpLevel = player.experienceLevel;
        xpProgress = player.experienceProgress;
        xpTotal = player.totalExperience;
        LOGGER.info("[DungeonTrain] keepInventory carry: captured {} item stack(s) + level {} XP for {}",
                saved.size(), xpLevel, player.getName().getString());
    }

    /** True only when a snapshot is present and belongs to {@code id}. */
    public static boolean isPresentFor(UUID id) {
        return slots != null && id != null && id.equals(playerId);
    }

    /**
     * Re-apply the captured inventory + experience onto {@code player}, then
     * clear the holder (one-shot). Slots that fail to parse are skipped so a
     * single bad stack can't drop the whole carry. Intended for the new world's
     * first {@link PlayerLoggedInEvent}.
     */
    public static void restore(ServerPlayer player) {
        try {
            HolderLookup.Provider registries = player.registryAccess();
            Inventory inv = player.getInventory();
            int applied = 0;
            if (slots != null) {
                for (SavedSlot s : slots) {
                    if (s.slot() < 0 || s.slot() >= inv.getContainerSize()) {
                        continue;
                    }
                    ItemStack stack = ItemStack.parse(registries, s.tag()).orElse(ItemStack.EMPTY);
                    if (!stack.isEmpty()) {
                        inv.setItem(s.slot(), stack);
                        applied++;
                    }
                }
            }
            // Mirror vanilla ServerPlayer.restoreFrom: assign the three XP
            // fields directly; ServerPlayer.doTick re-sends them to the client.
            player.experienceLevel = xpLevel;
            player.experienceProgress = xpProgress;
            player.totalExperience = xpTotal;
            // Push the restored stacks to the client now rather than waiting for
            // the next container refresh.
            player.inventoryMenu.broadcastChanges();
            LOGGER.info("[DungeonTrain] keepInventory carry: restored {} item stack(s) + level {} XP onto {}",
                    applied, xpLevel, player.getName().getString());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] keepInventory carry: restore failed; player keeps the fresh-world inventory", e);
        } finally {
            clear();
        }
    }

    /** Drop any pending snapshot (off path, title-screen path, post-restore). */
    public static void clear() {
        playerId = null;
        slots = null;
        xpLevel = 0;
        xpProgress = 0.0f;
        xpTotal = 0;
    }
}
