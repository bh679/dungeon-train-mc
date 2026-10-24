package games.brennan.dungeontrain.perks.effects.lover;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Good Gifter (Lover, tier 2): animals accept offerings they would normally refuse, by the
 * rules in {@link GoodGifterMath}. Runs only when vanilla would not already treat the held item
 * as that animal's food, so normal feeding is untouched. Server side only; the client sees the
 * usual swing and the server's hearts/smoke particles.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class GoodGifterEffects {

    /** Entity event bytes the client turns into heart (tamed) or smoke (not yet) particles. */
    private static final byte TAME_SUCCESS_EVENT = 7;
    private static final byte TAME_FAIL_EVENT = 6;

    private GoodGifterEffects() {}

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getTarget() instanceof Animal animal)) return;
        if (player.isSecondaryUseActive()) return; // keep sneak interactions (sit toggle, horse inventory)
        int rank = Perks.rank(player, LoverPerkIds.GOOD_GIFTER);
        if (rank <= 0) return;
        ItemStack stack = event.getItemStack();
        if (animal.isFood(stack)) return; // vanilla handles its own food
        boolean edible = stack.has(DataComponents.FOOD);
        boolean empty = stack.isEmpty();

        boolean handled;
        if (animal instanceof TamableAnimal tamable && !tamable.isTame()) {
            handled = GoodGifterMath.acceptsForTaming(rank, edible, empty) && tryTame(player, tamable, stack);
        } else if (animal instanceof AbstractHorse horse && !horse.isTamed()) {
            handled = GoodGifterMath.acceptsAsFood(rank, edible, empty) && feedHorse(player, horse, stack);
        } else {
            handled = GoodGifterMath.acceptsAsFood(rank, edible, empty) && feed(player, animal, stack);
        }
        if (handled) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    private static boolean tryTame(ServerPlayer player, TamableAnimal animal, ItemStack offering) {
        if (animal instanceof NeutralMob neutral && neutral.isAngry()) return false;
        if (animal.isBaby() && !offering.isEmpty()) return false; // let vanilla age babies up
        offering.consume(1, player);
        if (animal.getRandom().nextInt(GoodGifterMath.TAME_ONE_IN) == 0 && !EventHooks.onAnimalTame(animal, player)) {
            animal.tame(player);
            animal.getNavigation().stop();
            animal.setTarget(null);
            if (animal instanceof Wolf) animal.setOrderedToSit(true);
            animal.level().broadcastEntityEvent(animal, TAME_SUCCESS_EVENT);
        } else {
            animal.level().broadcastEntityEvent(animal, TAME_FAIL_EVENT);
        }
        return true;
    }

    private static boolean feedHorse(ServerPlayer player, AbstractHorse horse, ItemStack offering) {
        if (horse.getTemper() >= horse.getMaxTemper()) return false;
        offering.consume(1, player);
        horse.modifyTemper(GoodGifterMath.HORSE_TEMPER);
        horse.playSound(SoundEvents.HORSE_EAT, 1.0F, 1.0F);
        return true;
    }

    private static boolean feed(ServerPlayer player, Animal animal, ItemStack offering) {
        int age = animal.getAge();
        if (age == 0 && animal.canFallInLove()) {
            offering.consume(1, player);
            animal.setInLove(player);
            return true;
        }
        if (animal.isBaby()) {
            offering.consume(1, player);
            animal.ageUp((int) ((float) (-age / 20) * 0.1F), true);
            return true;
        }
        return false;
    }
}
