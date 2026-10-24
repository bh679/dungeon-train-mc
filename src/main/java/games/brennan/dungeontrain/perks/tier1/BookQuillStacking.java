package games.brennan.dungeontrain.perks.tier1;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.PerkEffect;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.TierOnePerks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Stackable Book and Quill (Archivist): blank book-and-quills in the player's inventory stack to
 * {@code 1 + rank} (vanilla 1). The stack size is a component stamped on the item, not a true
 * per-player rule, so: only blank books (no written content) are stamped, the stamp is removed
 * when a book leaves the player (tossed, dropped on death) and when the rank drops to 0, and the
 * inventory is re-walked once a second so picked-up or crafted books catch up.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BookQuillStacking implements PerkEffect {

    /** Vanilla stack limit; the perk never goes past it. */
    public static final int MAX_STACK = 16;
    private static final int SWEEP_PERIOD_TICKS = 20;

    /** Stack size a blank book-and-quill gets at {@code rank}: vanilla 1 at rank 0, capped at 16. */
    public static int stackSizeFor(int rank) {
        return Math.max(1, Math.min(MAX_STACK, 1 + rank));
    }

    /** A book-and-quill with nothing written: the item's default content component is empty, not absent. */
    public static boolean isBlankBookAndQuill(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.WRITABLE_BOOK)) return false;
        WritableBookContent content = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        return content == null || content.pages().isEmpty();
    }

    /** Stamp or strip one stack to match {@code rank}. Returns true when it changed. */
    public static boolean conform(ItemStack stack, int rank) {
        if (!isBlankBookAndQuill(stack)) return false;
        int want = stackSizeFor(rank);
        Integer have = stack.get(DataComponents.MAX_STACK_SIZE);
        if (want <= 1) {
            if (have == null) return false;
            // Setting the prototype's own value clears the patch entry instead of recording a removal.
            stack.set(DataComponents.MAX_STACK_SIZE, 1);
            return true;
        }
        if (have != null && have == want) return false;
        stack.set(DataComponents.MAX_STACK_SIZE, want);
        return true;
    }

    /** Strip the stamp so a book that left the player is a plain book again. */
    public static void strip(ItemStack stack) {
        if (stack.is(Items.WRITABLE_BOOK) && stack.getMaxStackSize() > 1) {
            stack.set(DataComponents.MAX_STACK_SIZE, 1);
        }
    }

    public static void sweep(ServerPlayer player) {
        int rank = Perks.rank(player, TierOnePerks.STACKABLE_BOOK_QUILL);
        Inventory inv = player.getInventory();
        boolean changed = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            changed |= conform(inv.getItem(i), rank);
        }
        if (changed) player.inventoryMenu.broadcastChanges();
    }

    @Override
    public void onRankChanged(ServerPlayer player, String perkId, int oldRank, int newRank) {
        if (TierOnePerks.STACKABLE_BOOK_QUILL.equals(perkId)) sweep(player);
    }

    @Override
    public void onPlayerReady(ServerPlayer player) {
        sweep(player);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % SWEEP_PERIOD_TICKS != 0) return;
        if (Perks.rank(player, TierOnePerks.STACKABLE_BOOK_QUILL) <= 0 && !holdsStamped(player)) return;
        sweep(player);
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
            if (s.is(Items.WRITABLE_BOOK) && s.getMaxStackSize() > 1) return true;
        }
        return false;
    }
}
