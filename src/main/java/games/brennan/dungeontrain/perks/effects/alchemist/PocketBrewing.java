package games.brennan.dungeontrain.perks.effects.alchemist;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.PerksGate;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Standless Brewing (Alchemist tier 2): the player's own brewing stand ({@link PocketBrewStand}), opened
 * from the inventory's Brew button ({@link PocketBrewOpenPacket}). It brews on the server every tick the
 * player has the perk, screen open or not, following a placed stand's rules (swapping the ingredient stops
 * a brew) at {@link PocketBrewingMath}'s brew time. It needs no fuel. Each bottle slot it gains comes with a
 * glass bottle in it ({@link PocketBrewStand#grantBottles}).
 *
 * <p>On death the contents drop with the player's other items (kept with keepInventory). Bottles in slots a
 * smaller rank no longer has go back to the inventory, or drop when it is full.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PocketBrewing {

    public static final String KEY_TITLE = "container.dungeontrain.pocket_brewing";
    private static final int OVERFLOW_CHECK_TICKS = 20;

    private PocketBrewing() {}

    /** The brewing rules the stand asks: a seam so the step is testable without a level. */
    public interface Mixer {
        boolean isIngredient(ItemStack ingredient);

        boolean hasMix(ItemStack bottle, ItemStack ingredient);

        ItemStack mix(ItemStack ingredient, ItemStack bottle);

        static Mixer of(PotionBrewing brewing) {
            return new Mixer() {
                @Override
                public boolean isIngredient(ItemStack ingredient) {
                    return brewing.isIngredient(ingredient);
                }

                @Override
                public boolean hasMix(ItemStack bottle, ItemStack ingredient) {
                    return brewing.hasMix(bottle, ingredient);
                }

                @Override
                public ItemStack mix(ItemStack ingredient, ItemStack bottle) {
                    return brewing.mix(ingredient, bottle);
                }
            };
        }
    }

    /** What one tick did, for the caller's sound and leftovers. */
    public record Step(boolean brewed, ItemStack leftover) {
        static final Step NONE = new Step(false, ItemStack.EMPTY);
    }

    public static PocketBrewStand stand(ServerPlayer player) {
        return player.getData(ModDataAttachments.POCKET_BREWING.get());
    }

    /**
     * One server tick of {@code stand} at {@code rank}, as vanilla's {@code BrewingStandBlockEntity.serverTick}
     * with no fuel. {@code strengthen} is Brewmaster's roll for a bottle the brew changed.
     */
    public static Step tick(PocketBrewStand stand, int rank, Mixer mixer, UnaryOperator<ItemStack> strengthen) {
        if (rank <= 0) return Step.NONE;
        int bottles = PocketBrewingMath.bottleSlots(rank);
        stand.ensureBottles(bottles);

        Step step = Step.NONE;
        ItemStack ingredient = stand.getItem(PocketBrewStand.INGREDIENT_SLOT);
        boolean brewable = isBrewable(stand, bottles, mixer);
        if (stand.brewTime > 0) {
            stand.brewTime--;
            if (stand.brewTime == 0 && brewable) {
                step = brew(stand, bottles, mixer, strengthen);
            } else if (!brewable || !ingredient.is(stand.brewing)) {
                stand.brewTime = 0;
            }
        } else if (brewable) {
            stand.brewTotal = PocketBrewingMath.brewTicks(rank);
            stand.brewTime = stand.brewTotal;
            stand.brewing = ingredient.getItem();
        }
        if (stand.brewTime == 0) {
            stand.brewTotal = 0;
            stand.brewing = Items.AIR;
        }
        return step;
    }

    static boolean isBrewable(PocketBrewStand stand, int bottles, Mixer mixer) {
        ItemStack ingredient = stand.getItem(PocketBrewStand.INGREDIENT_SLOT);
        if (ingredient.isEmpty() || !mixer.isIngredient(ingredient)) return false;
        for (int i = 0; i < bottles; i++) {
            ItemStack bottle = stand.getItem(PocketBrewStand.FIRST_BOTTLE + i);
            if (!bottle.isEmpty() && mixer.hasMix(bottle, ingredient)) return true;
        }
        return false;
    }

    /** Vanilla's {@code doBrew} over every bottle slot; Brewmaster rolls each bottle the brew changed. */
    private static Step brew(PocketBrewStand stand, int bottles, Mixer mixer, UnaryOperator<ItemStack> strengthen) {
        ItemStack ingredient = stand.getItem(PocketBrewStand.INGREDIENT_SLOT);
        for (int i = 0; i < bottles; i++) {
            int slot = PocketBrewStand.FIRST_BOTTLE + i;
            ItemStack before = stand.getItem(slot);
            ItemStack after = mixer.mix(ingredient, before);
            if (!ItemStack.matches(before, after)) after = strengthen.apply(after);
            stand.setItem(slot, after);
        }
        ItemStack leftover = ItemStack.EMPTY;
        if (ingredient.hasCraftingRemainingItem()) {
            ItemStack remainder = ingredient.getCraftingRemainingItem();
            ingredient.shrink(1);
            if (ingredient.isEmpty()) {
                ingredient = remainder;
            } else {
                leftover = remainder;
            }
        } else {
            ingredient.shrink(1);
        }
        stand.setItem(PocketBrewStand.INGREDIENT_SLOT, ingredient);
        return new Step(true, leftover);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !PerksGate.isOn()) return;
        int rank = Perks.rank(player, AlchemistPerks.STANDLESS_BREWING);
        if (rank <= 0) return;
        PocketBrewStand stand = stand(player);
        stand.grantBottles(PocketBrewingMath.bottleSlots(rank)).forEach(b -> give(player, b));
        Step step = tick(stand, rank, Mixer.of(player.level().potionBrewing()), b -> Brewmaster.strengthen(player, b));
        if (step.brewed()) {
            player.playNotifySound(SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 1.0F, 1.0F);
            give(player, step.leftover());
        }
        if (player.tickCount % OVERFLOW_CHECK_TICKS == 0) {
            stand.takeBottlesPast(PocketBrewingMath.bottleSlots(rank)).forEach(b -> give(player, b));
            give(player, stand.takeRetired());
        }
    }

    /** Open the stand for {@code player}; nothing without the perk. */
    public static void open(ServerPlayer player) {
        int rank = Perks.rank(player, AlchemistPerks.STANDLESS_BREWING);
        if (rank <= 0) return;
        int bottles = PocketBrewingMath.bottleSlots(rank);
        PocketBrewStand stand = stand(player);
        stand.ensureBottles(bottles);
        stand.grantBottles(bottles).forEach(b -> give(player, b));
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new PocketBrewingMenu(id, inv, stand, bottles, PocketBrewingMenu.data(player, stand)),
                Component.translatable(KEY_TITLE)),
            buf -> buf.writeVarInt(bottles));
    }

    @SubscribeEvent
    public static void onDeathDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
        List<ItemStack> contents = stand(player).takeAll();
        for (ItemStack s : contents) {
            ItemEntity drop = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), s);
            drop.setDefaultPickUpDelay();
            event.getDrops().add(drop);
        }
    }

    /** A respawn with keepInventory, or the End exit, carries the stand over to the new player entity. */
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.getOriginal().hasData(ModDataAttachments.POCKET_BREWING.get())) return;
        player.setData(ModDataAttachments.POCKET_BREWING.get(),
            event.getOriginal().getData(ModDataAttachments.POCKET_BREWING.get()).copy());
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }
}
