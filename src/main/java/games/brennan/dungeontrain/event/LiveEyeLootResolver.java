package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.item.LiveHeadpiecePendingTag;
import games.brennan.dungeontrain.player.ServerLiveStatus;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
            return new ItemStack(ModItems.LIVE_HEADPIECE.get());
        }
        return tv();
    }

    /**
     * {@code player} sees {@code container}: resolve its pending Live Eyes, and start the cooldown if it
     * already holds a real one. Returns true when a slot changed (the caller marks the block dirty).
     */
    public static boolean resolveContainer(ServerPlayer player, Container container) {
        if (countRealLiveEyes(container) > 0) markSeen(player);
        boolean changed = false;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (!LiveHeadpiecePendingTag.isPending(container.getItem(i))) continue;
            container.setItem(i, resolve(player));
            changed = true;
        }
        return changed;
    }

    /** True when {@code container} holds a pending or real Live Eye — the only containers worth resolving. */
    public static boolean holdsAnyLiveEye(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(ModItems.LIVE_HEADPIECE.get())) return true;
        }
        return false;
    }

    /** A real (resolved) Live Eye in the inventory — worn included — or the ender chest. */
    static boolean holdsLiveEye(ServerPlayer player) {
        return countRealLiveEyes(player.getInventory()) > 0
            || countRealLiveEyes(player.getEnderChestInventory()) > 0;
    }

    private static int countRealLiveEyes(Container container) {
        int n = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(ModItems.LIVE_HEADPIECE.get()) && !LiveHeadpiecePendingTag.isPending(stack)) n++;
        }
        return n;
    }

    private static boolean onCooldown(ServerPlayer player) {
        return onCooldown(player.getData(ModDataAttachments.LIVE_EYE_SEEN_AT.get()), now(player));
    }

    private static void markSeen(ServerPlayer player) {
        player.setData(ModDataAttachments.LIVE_EYE_SEEN_AT.get(), now(player));
    }

    private static long now(ServerPlayer player) {
        return player.server.overworld().getGameTime();
    }

    /** Vista's TV — a required dependency, so the lookup only misses in a broken install. */
    private static ItemStack tv() {
        var item = BuiltInRegistries.ITEM.get(TV_ID);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }
}
