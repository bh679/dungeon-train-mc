package games.brennan.dungeontrain.player;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.compat.EnderChestLockBridge;
import games.brennan.dungeontrain.mixin.PlayerEnderChestAccessor;
import games.brennan.dungeontrain.mixin.PlayerEnderChestContainerAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * The Free Play Ender Chest expansion: 27 slots → 81 (three times the vanilla three rows), at the
 * player's request, kept for good.
 *
 * <p>A Free Play run's chest is locked onto EnderChestPersistence's creative slot by
 * {@link EnderChestLockBridge} — a per-player locker that already survives every world. This grows
 * that locker, and only that one: a legit run keeps vanilla's 27, and nothing here runs unless the
 * lock itself is active, so an expanded chest can never be a legit chest.</p>
 *
 * <p><b>How the capacity changes.</b> {@code PlayerEnderChestContainer} is fixed at 27 by its only
 * constructor, so the live container is <em>replaced</em>: a bigger one is built with
 * {@link #requestedSize()} raised for the duration (read by {@code PlayerEnderChestContainerSizeMixin}),
 * the stacks are copied across, and it is swapped in through {@link PlayerEnderChestAccessor}. ECP
 * serialises whatever container is live via vanilla's {@code createTag}/{@code fromTag}, so the 81-slot
 * chest round-trips through its store unchanged.</p>
 *
 * <p><b>Two questions, kept apart.</b> {@link #applyStored} answers "how big must the live container
 * be" from the player's record alone, without consulting Free Play — it runs at login <em>before</em>
 * ECP restores, and a 27-slot container would silently drop every stack beyond it on restore and save
 * the truncated chest back at logout. Whether the player is <em>shown</em> the extra rows is
 * {@link #showsExpanded}: capacity <em>and</em> Free Play. A legit run with the flag on file gets a
 * silently oversized container behind a vanilla 27-slot menu — indistinguishable from vanilla, since
 * nothing legit can address a slot past 27.</p>
 *
 * <p>Expansion is one-way. Shrinking would orphan up to 54 stacks; there is no shrink.</p>
 */
public final class EnderChestExpansion {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int COLUMNS = 9;
    public static final int VANILLA_ROWS = 3;
    public static final int EXPANDED_ROWS = VANILLA_ROWS * 3;
    public static final int VANILLA_SLOTS = VANILLA_ROWS * COLUMNS;
    public static final int EXPANDED_SLOTS = EXPANDED_ROWS * COLUMNS;

    /** The capacity {@code PlayerEnderChestContainer}'s constructor asks for; 27 except inside {@link #newContainer}. */
    private static final ThreadLocal<Integer> REQUESTED_SIZE = ThreadLocal.withInitial(() -> VANILLA_SLOTS);

    private EnderChestExpansion() {}

    /** Read by the constructor mixin. */
    public static int requestedSize() {
        return REQUESTED_SIZE.get();
    }

    /** Whether the feature is available at all: needs ECP's slot lock, or there is no "Free Play slot". */
    public static boolean available() {
        return EnderChestLockBridge.isActive();
    }

    /** Whether {@code player}'s live container already has the expanded capacity. */
    public static boolean hasExpandedCapacity(ServerPlayer player) {
        return player.getEnderChestInventory().getContainerSize() >= EXPANDED_SLOTS;
    }

    /** Whether {@code player} should see the nine-row chest: expanded, and in Free Play. */
    public static boolean showsExpanded(ServerPlayer player) {
        return available() && RunIntegrity.isCheated(player) && hasExpandedCapacity(player);
    }

    /** Whether {@code player} should be offered the expand button: Free Play, not yet expanded. */
    public static boolean canExpand(ServerPlayer player) {
        return available() && RunIntegrity.isCheated(player) && !hasExpandedCapacity(player);
    }

    /**
     * Rows the chest menu should show {@code player} right now — the live capacity when Free Play may
     * see it, vanilla's three otherwise. Never more than the live container holds, so a menu built on it
     * always passes {@code checkContainerSize}.
     */
    public static int visibleRows(ServerPlayer player) {
        return showsExpanded(player) ? EXPANDED_ROWS : VANILLA_ROWS;
    }

    /**
     * Grow the live container if this player's record says it was expanded. Login-time, before ECP's
     * restore (see the class comment for why the order matters); harmless to repeat.
     */
    public static void applyStored(ServerPlayer player) {
        if (!available() || hasExpandedCapacity(player)) return;
        UUID uuid = player.getUUID();
        boolean flagged = EnderChestExpansionStore.DEFAULT.isExpanded(uuid);
        // The flag is only a cache of the truth: items already beyond slot 27 on disk prove the expansion
        // even if the flag file went with a reinstall.
        boolean proven = !flagged && EnderChestLockBridge.freePlaySlotReachesBeyond(uuid, VANILLA_SLOTS);
        if (!flagged && !proven) return;
        if (proven) {
            EnderChestExpansionStore.DEFAULT.markExpanded(uuid); // heal the flag
        }
        grow(player);
        LOGGER.info("[DungeonTrain] Ender Chest capacity restored to {} slots for {} ({})",
            EXPANDED_SLOTS, player.getName().getString(), flagged ? "flag" : "stored items");
    }

    /**
     * The button's action: record the expansion and grow the live container. Returns false — and changes
     * nothing — unless {@link #canExpand} holds, so a stale or forged button click is inert.
     */
    public static boolean expand(ServerPlayer player) {
        if (!canExpand(player)) return false;
        EnderChestExpansionStore.DEFAULT.markExpanded(player.getUUID());
        grow(player);
        LOGGER.info("[DungeonTrain] {} expanded their Free Play Ender Chest to {} slots",
            player.getName().getString(), EXPANDED_SLOTS);
        return true;
    }

    /** Replace the live container with an {@link #EXPANDED_SLOTS}-slot copy. Idempotent. */
    private static void grow(ServerPlayer player) {
        PlayerEnderChestContainer old = player.getEnderChestInventory();
        if (old.getContainerSize() >= EXPANDED_SLOTS) return;
        PlayerEnderChestContainer big = newContainer(EXPANDED_SLOTS);
        for (int i = 0; i < old.getContainerSize(); i++) {
            big.setItem(i, old.getItem(i));
        }
        // Keep the chest it is being looked through, so the open menu still closes on walking away.
        EnderChestBlockEntity active = ((PlayerEnderChestContainerAccessor) old).dungeontrain$getActiveChest();
        if (active != null) {
            big.setActiveChest(active);
        }
        ((PlayerEnderChestAccessor) player).dungeontrain$setEnderChestInventory(big);
    }

    /** Build a container of {@code size} slots — the one place the constructor's 27 is overridden. */
    private static PlayerEnderChestContainer newContainer(int size) {
        REQUESTED_SIZE.set(size);
        try {
            return new PlayerEnderChestContainer();
        } finally {
            REQUESTED_SIZE.remove();
        }
    }
}
