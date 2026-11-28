package games.brennan.dungeontrain.perks.effects.tank;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.PerkEffect;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.PerksGate;
import games.brennan.dungeontrain.perks.StackStamp;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Stack Soups (Tank tier 2, perk id {@code hunger}, which it replaced): soups in the player's inventory
 * stack {@value TankPerks#SOUP_STACK_PER_RANK} higher per point, so 3 at one point, 5 at two, up to the
 * game's 99. A soup is anything in {@code c:foods/soup} (mushroom stew, beetroot soup, rabbit stew,
 * suspicious stew, and mod soups that tag themselves). Only identical soups stack: suspicious stews with
 * different effects stay apart.
 *
 * <p>Same mechanism as Stack Potions ({@code alchemist.PotionStacking}): a stack-size component stamped
 * once a second, on a rank change and when the player is ready; stripped when a soup leaves the player;
 * never stamped below its own count. The stamp never goes below the item's own default either, so a
 * Farmers' Delight install with its stackable soups on (16) is never cut down.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SoupStacking implements PerkEffect {

    private static final int SWEEP_PERIOD_TICKS = 20;

    public static boolean isSoup(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Tags.Items.FOODS_SOUP);
    }

    /** The item's own max stack size, before any stamp: 1 in vanilla, 16 with Farmers' Delight's soups on. */
    static int defaultMax(ItemStack stack) {
        return Math.max(1, stack.getPrototype().getOrDefault(DataComponents.MAX_STACK_SIZE, 1));
    }

    /** Stamp or strip one stack to match {@code rank}. Returns true when it changed. */
    public static boolean conform(ItemStack stack, int rank) {
        if (!isSoup(stack)) return false;
        int want = TankPerks.soupStamp(rank, stack.getCount(), defaultMax(stack));
        if (stack.getMaxStackSize() == want) return false;
        // Setting the prototype's own value clears the patch entry instead of recording a removal.
        stack.set(DataComponents.MAX_STACK_SIZE, want);
        return true;
    }

    /** A soup that left the player keeps only what its own count needs. */
    public static void strip(ItemStack stack) {
        conform(stack, 0);
    }

    public static void sweep(ServerPlayer player) {
        int rank = Perks.rank(player, TankPerks.STACK_SOUPS);
        Inventory inv = player.getInventory();
        boolean changed = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            changed |= conform(inv.getItem(i), rank);
        }
        if (changed) player.inventoryMenu.broadcastChanges();
    }

    @Override
    public void onRankChanged(ServerPlayer player, String perkId, int oldRank, int newRank) {
        if (TankPerks.STACK_SOUPS.equals(perkId)) sweep(player);
    }

    @Override
    public void onPlayerReady(ServerPlayer player) {
        sweep(player);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!PerksGate.isOn() || player.tickCount % SWEEP_PERIOD_TICKS != 0) return;
        if (Perks.rank(player, TankPerks.STACK_SOUPS) > 0 || holdsStamped(player)) sweep(player);
    }

    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        strip(event.getEntity().getItem());
    }

    @SubscribeEvent
    public static void onDeathDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) return;
        event.getDrops().forEach(drop -> strip(drop.getItem()));
    }

    private static boolean holdsStamped(ServerPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (isSoup(s) && s.getMaxStackSize() > Math.max(defaultMax(s), s.getCount())) return true;
        }
        return false;
    }
}
