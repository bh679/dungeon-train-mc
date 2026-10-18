package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.item.LiveHeadpiecePendingTag;
import games.brennan.dungeontrain.player.ServerLiveStatus;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.registry.ModItems;
import games.brennan.ediblebackpacks.registry.ModAttachments;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Decides what a loot-rolled Live Eye becomes for the player who first sees it.
 *
 * <p>Train loot is baked per carriage with no player in sight, so a Live Eye slot bakes a
 * {@link LiveHeadpiecePendingTag pending} Live Eye. The first time a player sees it — opening the
 * container, looking at it with Jade, breaking it, or the stack arriving in their inventory — it
 * resolves to a real Live Eye or, whenever the player can't have one, a TV:</p>
 * <ul>
 *   <li>a Kid with Livestreaming off never gets one ({@link ContentModeMirror#mayLootLiveHeadpiece});</li>
 *   <li>one at a time — a player already holding a Live Eye (inventory, worn, or ender chest) gets a TV;</li>
 *   <li>after seeing a Live Eye in a container, none for {@link #COOLDOWN_TICKS} (15 min);</li>
 *   <li>while anyone is broadcasting on the Live Feed only {@link #LIVE_KEEP_CHANCE} of slots stay a
 *       Live Eye ({@link ServerLiveStatus#isAnyoneLive}).</li>
 * </ul>
 * A granted eye stays {@link LiveHeadpiecePendingTag#isUnclaimed unclaimed} until a player takes it, and
 * is checked again on every sighting: anyone already holding a Live Eye sees it become a TV. A player
 * carries at most one ({@link #extrasOver}; enforced by {@link LiveHeadpieceLootGate}).
 * TVs are ordinary loot and are never limited.
 */
public final class LiveEyeLootResolver {

    /** How long after seeing a Live Eye in a container a player gets no other: 15 minutes. */
    public static final long COOLDOWN_TICKS = 15L * 60L * 20L;
    /** Share of Live Eye slots that stay a Live Eye while someone is live (otherwise always). */
    public static final float LIVE_KEEP_CHANCE = 0.10f;

    private static final ResourceLocation TV_ID = ResourceLocation.fromNamespaceAndPath("vista", "television");

    public enum Outcome { LIVE_EYE, TV }

    private LiveEyeLootResolver() {}

    /** The rule, pure for the tests. {@code roll} is uniform in [0, 1). */
    public static Outcome decide(boolean mayLoot, boolean holdsLiveEye, boolean onCooldown,
                                 boolean anyoneLive, float roll) {
        return decide(mayLoot, holdsLiveEye, onCooldown, anyoneLive, false, roll);
    }

    /** As above; a player banned from the Live Eye by the text-filter ladder ({@code LiveModeration}) always finds a TV. */
    public static Outcome decide(boolean mayLoot, boolean holdsLiveEye, boolean onCooldown,
                                 boolean anyoneLive, boolean banned, float roll) {
        if (banned) return Outcome.TV;
        if (!mayLoot || holdsLiveEye || onCooldown) return Outcome.TV;
        if (anyoneLive && roll >= LIVE_KEEP_CHANCE) return Outcome.TV;
        return Outcome.LIVE_EYE;
    }

    /** True while a seen-at stamp is within the cooldown. A clock that ran backwards expires it. */
    public static boolean onCooldown(long seenAt, long now) {
        return seenAt > 0 && now >= seenAt && now - seenAt < COOLDOWN_TICKS;
    }

    /**
     * What one pending Live Eye becomes for {@code player}. A granted Live Eye starts the cooldown, so
     * a second pending slot seen in the same pass becomes a TV.
     */
    public static ItemStack resolve(ServerPlayer player) {
        Outcome outcome = decide(
            ContentModeMirror.mayLootLiveHeadpiece(player),
            holdsLiveEye(player),
            onCooldown(player),
            ServerLiveStatus.isAnyoneLive(player.server),
            LiveModeration.isBanned(player.getUUID()),
            player.getRandom().nextFloat());
        if (outcome == Outcome.LIVE_EYE) {
            markSeen(player);
            ItemStack eye = new ItemStack(ModItems.LIVE_HEADPIECE.get());
            LiveHeadpiecePendingTag.markUnclaimed(eye);
            return eye;
        }
        return tv();
    }

    /**
     * {@code player} sees {@code container}: resolve its pending Live Eyes, turn its unclaimed ones into
     * TVs when the player already holds a Live Eye, and start the cooldown if a real one is left. Returns
     * true when a slot changed (the caller marks the block dirty).
     */
    public static boolean resolveContainer(ServerPlayer player, Container container) {
        boolean changed = false;
        if (holdsLiveEye(player)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (!LiveHeadpiecePendingTag.isUnclaimed(container.getItem(i))) continue;
                container.setItem(i, tv());
                changed = true;
            }
        }
        if (countRealLiveEyes(container) > 0) markSeen(player);
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (!LiveHeadpiecePendingTag.isPending(container.getItem(i))) continue;
            container.setItem(i, resolve(player));
            changed = true;
        }
        return changed;
    }

    /** How many of {@code held} real Live Eyes are over the one a player may carry. */
    public static int extrasOver(int held) {
        return Math.max(0, held - 1);
    }

    /** True when {@code container} holds a pending or real Live Eye — the only containers worth resolving. */
    public static boolean holdsAnyLiveEye(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(ModItems.LIVE_HEADPIECE.get())) return true;
        }
        return false;
    }

    /** A real (resolved) Live Eye in the inventory (worn included), ender chest or backpack, shulkers in any included. */
    public static boolean holdsLiveEye(ServerPlayer player) {
        return heldLiveEyes(player) > 0;
    }

    /** Every real Live Eye {@code player} holds: inventory (worn included), ender chest and backpack, packed ones too. */
    public static int heldLiveEyes(ServerPlayer player) {
        return countRealLiveEyes(player.getInventory()) + countRealLiveEyes(player.getEnderChestInventory())
            + LiveEyeHoldings.count(backpack(player));
    }

    /** The player's Edible Backpack slots (a jarJar'd sibling, so always present). */
    static ItemStackHandler backpack(ServerPlayer player) {
        return player.getData(ModAttachments.BACKPACK).items();
    }

    /**
     * Resolved (not pending) Live Eyes in {@code container}, counting those packed in shulker boxes and
     * bundles ({@link LiveEyeHoldings}) — any side, so a client can count its own inventory.
     */
    public static int countRealLiveEyes(Container container) {
        return LiveEyeHoldings.count(container);
    }

    private static boolean onCooldown(ServerPlayer player) {
        return onCooldown(player.getData(ModDataAttachments.LIVE_EYE_SEEN_AT.get()), now(player));
    }

    /** Start {@code player}'s Live Eye cooldown now (a granted or gifted eye). */
    public static void markSeen(ServerPlayer player) {
        player.setData(ModDataAttachments.LIVE_EYE_SEEN_AT.get(), now(player));
    }

    private static long now(ServerPlayer player) {
        return player.server.overworld().getGameTime();
    }

    /** Vista's TV — a required dependency, so the lookup only misses in a broken install. */
    static ItemStack tv() {
        var item = BuiltInRegistries.ITEM.get(TV_ID);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }
}
