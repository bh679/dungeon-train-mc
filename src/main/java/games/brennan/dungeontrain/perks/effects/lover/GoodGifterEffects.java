package games.brennan.dungeontrain.perks.effects.lover;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.TierOnePerks;
import games.brennan.dungeontrain.perks.tier1.Friendliness;
import games.brennan.dungeontrain.perks.effects.lover.GoodGifterMath.Offering;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Good Gifter (Lover, tier 2): animals accept offerings they would normally refuse, and every
 * offering tames more often, by the rules in {@link GoodGifterMath} (offering kinds sorted by
 * {@link GoodGifterOfferings}). The animal's right food keeps vanilla handling except for taming,
 * where the perk raises the odds. Server side only; the client sees the usual swing and the
 * server's hearts/smoke particles.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class GoodGifterEffects {

    /** Entity event bytes the client turns into heart (tamed) or smoke (not yet) particles. */
    private static final byte TAME_SUCCESS_EVENT = 7;
    private static final byte TAME_FAIL_EVENT = 6;

    private GoodGifterEffects() {}

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        GoodGifterOfferings.clearCache();
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getTarget() instanceof Animal animal)) return;
        if (player.isSecondaryUseActive()) return; // keep sneak interactions (sit toggle, horse inventory)
        int rank = Perks.rank(player, LoverPerkIds.GOOD_GIFTER);
        if (rank <= 0) return;
        ItemStack stack = event.getItemStack();
        if (animal instanceof Parrot && stack.is(ItemTags.PARROT_POISONOUS_FOOD)) return; // vanilla poisons it
        Offering offering = GoodGifterOfferings.classify(animal, stack);
        if (!GoodGifterMath.unlocked(offering, rank)) return;

        boolean handled;
        if (animal instanceof TamableAnimal tamable && !tamable.isTame()) {
            double chance = GoodGifterMath.tameChance(offering, rank, baseTameChance(tamable));
            // Good Gifter skips the vanilla roll, so Friendliness's extra rolls are applied here.
            chance = Friendliness.withExtraRolls(chance, Perks.rank(player, TierOnePerks.FRIENDLINESS));
            handled = tryTame(player, tamable, stack, chance);
        } else if (offering == Offering.RIGHT_FOOD) {
            return; // vanilla feeds, breeds and tempers with the right food
        } else if (animal instanceof AbstractHorse horse && !horse.isTamed()) {
            handled = feedHorse(player, horse, stack, GoodGifterMath.horseTemper(offering, rank));
        } else {
            handled = GoodGifterMath.feeds(offering, rank) && feed(player, animal, stack);
        }
        if (handled) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    private static double baseTameChance(TamableAnimal animal) {
        return animal instanceof Parrot ? GoodGifterMath.PARROT_TAME_CHANCE : GoodGifterMath.DEFAULT_TAME_CHANCE;
    }

    private static boolean tryTame(ServerPlayer player, TamableAnimal animal, ItemStack offering, double chance) {
        if (animal instanceof NeutralMob neutral && neutral.isAngry()) return false;
        if (animal.isBaby() && !offering.isEmpty()) return false; // let vanilla age babies up
        offering.consume(1, player);
        if (animal.getRandom().nextDouble() < chance && !EventHooks.onAnimalTame(animal, player)) {
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

    private static boolean feedHorse(ServerPlayer player, AbstractHorse horse, ItemStack offering, int temper) {
        if (temper <= 0 || horse.getTemper() >= horse.getMaxTemper()) return false;
        offering.consume(1, player);
        horse.modifyTemper(temper);
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
