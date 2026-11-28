package games.brennan.dungeontrain.perks.effects.alchemist;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.PerkEffect;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.PerksGate;
import games.brennan.dungeontrain.perks.StackStamp;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Stack Potions (Alchemist tier 1): drinkable, splash and lingering potions in the player's inventory
 * stack to {@code 1 + rank} (vanilla 1). Only identical potions stack: the stack size is a component
 * stamped on each potion, so two potions merge only when everything else about them matches.
 *
 * <p>As Stackable Book and Quill ({@code perks/tier1/BookQuillStacking}): stamped once a second, on a
 * rank change and when the player is ready; stripped when a potion leaves the player. Unlike the book,
 * a stack holding more than one potion is never stamped below its own count, so a dropped stack of five
 * stays a valid stack of five and only stacks back down as it is used.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PotionStacking implements PerkEffect {

    /** The game's own ceiling: the max-stack-size component only encodes 1..99. Not a design cap. */
    public static final int MAX_STACK = StackStamp.MAX_STACK;
    private static final int SWEEP_PERIOD_TICKS = 20;

    /** Stack size a potion gets at {@code rank}: vanilla 1 at rank 0, 2 at 1 point, up to the game's 99. */
    public static int stackSizeFor(int rank) {
        return Math.max(1, Math.min(MAX_STACK, 1 + Math.max(0, rank)));
    }

    /** The stamp a stack of {@code count} gets at {@code rank}: never below its own count, never past 99. */
    public static int stampFor(int rank, int count) {
        return Math.min(MAX_STACK, Math.max(stackSizeFor(rank), Math.max(1, count)));
    }

    public static boolean isPotion(ItemStack stack) {
        return !stack.isEmpty()
            && (stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION));
    }

    /** Two potions of the same item whose components differ at most by the stack-size stamp. */
    public static boolean sameIgnoringStamp(ItemStack a, ItemStack b) {
        return isPotion(a) && StackStamp.sameIgnoringStamp(a, b);
    }

    /** Stamp or strip one stack to match {@code rank}. Returns true when it changed. */
    public static boolean conform(ItemStack stack, int rank) {
        if (!isPotion(stack)) return false;
        int want = stampFor(rank, stack.getCount());
        if (stack.getMaxStackSize() == want) return false;
        // Setting the prototype's own value (1) clears the patch entry instead of recording a removal.
        stack.set(DataComponents.MAX_STACK_SIZE, want);
        return true;
    }

    /** A potion that left the player keeps only what its own count needs. */
    public static void strip(ItemStack stack) {
        conform(stack, 0);
    }

    public static void sweep(ServerPlayer player) {
        int rank = Perks.rank(player, AlchemistPerks.STACK_POTIONS);
        Inventory inv = player.getInventory();
        boolean changed = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            changed |= conform(inv.getItem(i), rank);
        }
        if (changed) player.inventoryMenu.broadcastChanges();
    }

    @Override
    public void onRankChanged(ServerPlayer player, String perkId, int oldRank, int newRank) {
        if (AlchemistPerks.STACK_POTIONS.equals(perkId)) sweep(player);
    }

    @Override
    public void onPlayerReady(ServerPlayer player) {
        sweep(player);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!PerksGate.isOn() || player.tickCount % SWEEP_PERIOD_TICKS != 0) return;
        if (Perks.rank(player, AlchemistPerks.STACK_POTIONS) > 0 || holdsStamped(player)) sweep(player);
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
            if (isPotion(s) && s.getMaxStackSize() > Math.max(1, s.getCount())) return true;
        }
        return false;
    }
}
