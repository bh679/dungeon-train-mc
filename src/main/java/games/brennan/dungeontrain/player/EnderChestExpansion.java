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
 * The Free Play Ender Chest expansion: vanilla's 27 slots → {@link EnderChestLayout#EXPANDED_SLOTS} (a
 * 19×6 grid — three rows above, five columns each side), at the player's request, kept across worlds,
 * and reversible only while the extra slots are empty.
 *
 * <p>A Free Play run's chest is locked onto EnderChestPersistence's creative slot by
 * {@link EnderChestLockBridge} — a per-player locker that already survives every world. This grows
 * that locker, and only that one: a legit run keeps vanilla's 27, and nothing here runs unless the
 * lock itself is active, so an expanded chest can never be a legit chest.</p>
 *
 * <p><b>How the capacity changes.</b> {@code PlayerEnderChestContainer} is fixed at 27 by its only
 * constructor, so the live container is <em>replaced</em>: one of the wanted size is built with
 * {@link #requestedSize()} raised for the duration (read by {@code PlayerEnderChestContainerSizeMixin}),
 * the stacks are copied across by index, and it is swapped in through {@link PlayerEnderChestAccessor}.
 * ECP serialises whatever container is live via vanilla's {@code createTag}/{@code fromTag}, so the
 * bigger chest round-trips through its store unchanged; {@link EnderChestLayout}'s index contract keeps
 * the vanilla 27 at indices 0–26 in both sizes, so nothing moves on either transition.</p>
 *
 * <p><b>Two questions, kept apart.</b> {@link #applyStored} answers "how big must the live container
 * be" from the player's record alone, without consulting Free Play — it runs at login <em>before</em>
 * ECP restores, and a 27-slot container would silently drop every stack beyond it on restore and save
 * the truncated chest back at logout. Whether the player is <em>shown</em> the extra slots is
 * {@link #showsExpanded}: capacity <em>and</em> Free Play. A legit run with the flag on file gets a
 * silently oversized container behind a vanilla 27-slot menu — indistinguishable from vanilla, since
 * nothing legit can address a slot past 27.</p>
 *
 * <p><b>Shrinking</b> is refused while any extra slot holds an item — the only way to lose something
 * here would be to drop a slot with contents, so that path does not exist.</p>
 */
public final class EnderChestExpansion {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int VANILLA_SLOTS = EnderChestLayout.VANILLA_SLOTS;
    public static final int EXPANDED_SLOTS = EnderChestLayout.EXPANDED_SLOTS;

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

    /** Whether {@code player} should see the expanded chest: expanded, and in Free Play. */
    public static boolean showsExpanded(ServerPlayer player) {
        return available() && RunIntegrity.isCheated(player) && hasExpandedCapacity(player);
    }

    /** Whether {@code player} should be offered the expand button: Free Play, not yet expanded. */
    public static boolean canExpand(ServerPlayer player) {
        return available() && RunIntegrity.isCheated(player) && !hasExpandedCapacity(player);
    }

    /** Whether the shrink button applies at all (it is enabled only while {@link #extraSlotsEmpty}). */
    public static boolean canOfferShrink(ServerPlayer player) {
        return showsExpanded(player);
    }

    /** True when every slot past the vanilla 27 is empty — the precondition for shrinking. */
    public static boolean extraSlotsEmpty(ServerPlayer player) {
        PlayerEnderChestContainer chest = player.getEnderChestInventory();
        for (int i = VANILLA_SLOTS; i < chest.getContainerSize(); i++) {
            if (!chest.getItem(i).isEmpty()) return false;
        }
        return true;
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
        resize(player, EXPANDED_SLOTS);
        LOGGER.info("[DungeonTrain] Ender Chest capacity restored to {} slots for {} ({})",
            EXPANDED_SLOTS, player.getName().getString(), flagged ? "flag" : "stored items");
    }

    /**
     * The expand button's action: record the expansion and grow the live container. Returns false — and
     * changes nothing — unless {@link #canExpand} holds, so a stale or forged button click is inert.
     */
    public static boolean expand(ServerPlayer player) {
        if (!canExpand(player)) return false;
        EnderChestExpansionStore.DEFAULT.markExpanded(player.getUUID());
        resize(player, EXPANDED_SLOTS);
        LOGGER.info("[DungeonTrain] {} expanded their Free Play Ender Chest to {} slots",
            player.getName().getString(), EXPANDED_SLOTS);
        return true;
    }

    /**
     * The shrink button's action: back to the vanilla 27, only while every extra slot is empty. Returns
     * false — and changes nothing — otherwise; the server re-checks emptiness itself rather than trusting
     * the client's enabled state.
     */
    public static boolean shrink(ServerPlayer player) {
        if (!canOfferShrink(player) || !extraSlotsEmpty(player)) return false;
        EnderChestExpansionStore.DEFAULT.clearExpanded(player.getUUID());
        resize(player, VANILLA_SLOTS);
        LOGGER.info("[DungeonTrain] {} shrank their Free Play Ender Chest back to {} slots",
            player.getName().getString(), VANILLA_SLOTS);
        return true;
    }

    /**
     * Replace the live container with a {@code size}-slot copy, stacks carried across by index. Only ever
     * called to shrink onto empty slots or to grow, so no stack can fall off the end.
     */
    private static void resize(ServerPlayer player, int size) {
        PlayerEnderChestContainer old = player.getEnderChestInventory();
        if (old.getContainerSize() == size) return;
        PlayerEnderChestContainer next = newContainer(size);
        int carried = Math.min(size, old.getContainerSize());
        for (int i = 0; i < carried; i++) {
            next.setItem(i, old.getItem(i));
        }
        // Keep the chest it is being looked through, so the open menu still closes on walking away.
        EnderChestBlockEntity active = ((PlayerEnderChestContainerAccessor) old).dungeontrain$getActiveChest();
        if (active != null) {
            next.setActiveChest(active);
        }
        ((PlayerEnderChestAccessor) player).dungeontrain$setEnderChestInventory(next);
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
