package games.brennan.dungeontrain.perks.effects.alchemist;

import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pocket stand's brewing step, Brewmaster's upgrade and merge, and potion stamping, on real items. */
class PotionBrewingTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Redstone turns a potion into a splash potion; nothing else brews. */
    private static final PocketBrewing.Mixer MIXER = new PocketBrewing.Mixer() {
        @Override
        public boolean isIngredient(ItemStack ingredient) {
            return ingredient.is(Items.REDSTONE);
        }

        @Override
        public boolean hasMix(ItemStack bottle, ItemStack ingredient) {
            return bottle.is(Items.POTION) && ingredient.is(Items.REDSTONE);
        }

        @Override
        public ItemStack mix(ItemStack ingredient, ItemStack bottle) {
            return bottle.is(Items.POTION) ? new ItemStack(Items.SPLASH_POTION) : bottle;
        }
    };

    private static PocketBrewStand stand(int bottles) {
        PocketBrewStand s = new PocketBrewStand();
        s.ensureBottles(bottles);
        return s;
    }

    @Test
    void blazePowderBecomesAFullBarAndABrewCostsOne() {
        PocketBrewStand s = stand(1);
        s.setItem(PocketBrewStand.FUEL_SLOT, new ItemStack(Items.BLAZE_POWDER, 2));
        s.setItem(PocketBrewStand.INGREDIENT_SLOT, new ItemStack(Items.REDSTONE, 3));
        s.setItem(PocketBrewStand.FIRST_BOTTLE, new ItemStack(Items.POTION));
        PocketBrewing.tick(s, 1, MIXER, b -> b);
        assertEquals(1, s.getItem(PocketBrewStand.FUEL_SLOT).getCount());
        assertEquals(PocketBrewingMath.FUEL_MAX - 1, s.fuel());
        assertEquals(PocketBrewingMath.brewTicks(1), s.brewTime());
    }

    @Test
    void aBrewFinishesAfterItsTimeAndStrengthensOnlyChangedBottles() {
        PocketBrewStand s = stand(2);
        s.fuel = 5;
        s.setItem(PocketBrewStand.INGREDIENT_SLOT, new ItemStack(Items.REDSTONE, 3));
        s.setItem(PocketBrewStand.FIRST_BOTTLE, new ItemStack(Items.POTION));
        s.setItem(PocketBrewStand.FIRST_BOTTLE + 1, new ItemStack(Items.GLASS_BOTTLE));
        int[] strengthened = {0};
        boolean brewed = false;
        for (int t = 0; t <= PocketBrewingMath.brewTicks(5); t++) {
            brewed |= PocketBrewing.tick(s, 5, MIXER, b -> { strengthened[0]++; return b; }).brewed();
        }
        assertTrue(brewed);
        assertTrue(s.getItem(PocketBrewStand.FIRST_BOTTLE).is(Items.SPLASH_POTION));
        assertTrue(s.getItem(PocketBrewStand.FIRST_BOTTLE + 1).is(Items.GLASS_BOTTLE));
        assertEquals(1, strengthened[0]);
        assertEquals(2, s.getItem(PocketBrewStand.INGREDIENT_SLOT).getCount());
        assertEquals(4, s.fuel());
    }

    @Test
    void swappingTheIngredientStopsTheBrew() {
        PocketBrewStand s = stand(1);
        s.fuel = 5;
        s.setItem(PocketBrewStand.INGREDIENT_SLOT, new ItemStack(Items.REDSTONE));
        s.setItem(PocketBrewStand.FIRST_BOTTLE, new ItemStack(Items.POTION));
        PocketBrewing.tick(s, 1, MIXER, b -> b);
        assertTrue(s.brewTime() > 0);
        s.setItem(PocketBrewStand.INGREDIENT_SLOT, ItemStack.EMPTY);
        PocketBrewing.tick(s, 1, MIXER, b -> b);
        assertEquals(0, s.brewTime());
    }

    @Test
    void freeFuelArrivesOnItsClockUpToAFullBar() {
        PocketBrewStand s = stand(1);
        for (int t = 0; t < PocketBrewingMath.freeFuelTicks(3); t++) PocketBrewing.tick(s, 3, MIXER, b -> b);
        assertEquals(1, s.fuel());
        s.fuel = PocketBrewingMath.FUEL_MAX;
        for (int t = 0; t < PocketBrewingMath.freeFuelTicks(3) * 2; t++) PocketBrewing.tick(s, 3, MIXER, b -> b);
        assertEquals(PocketBrewingMath.FUEL_MAX, s.fuel());
    }

    @Test
    void withoutThePerkNothingTicks() {
        PocketBrewStand s = stand(1);
        PocketBrewing.tick(s, 0, MIXER, b -> b);
        assertEquals(0, s.freeFuelTicks());
    }

    @Test
    void bottlesPastASmallerRankComeOut() {
        PocketBrewStand s = stand(3);
        s.setItem(PocketBrewStand.FIRST_BOTTLE + 2, new ItemStack(Items.POTION));
        List<ItemStack> out = s.takeBottlesPast(1);
        assertEquals(1, out.size());
        assertTrue(s.getItem(PocketBrewStand.FIRST_BOTTLE + 2).isEmpty());
    }

    @Test
    void theStandSavesAndLoads() {
        PocketBrewStand s = stand(2);
        s.fuel = 7;
        s.setItem(PocketBrewStand.FUEL_SLOT, new ItemStack(Items.BLAZE_POWDER, 4));
        s.setItem(PocketBrewStand.FIRST_BOTTLE + 1, new ItemStack(Items.GLASS_BOTTLE));
        var json = PocketBrewStand.CODEC.encodeStart(JsonOps.INSTANCE, s).getOrThrow();
        PocketBrewStand back = PocketBrewStand.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(7, back.fuel());
        assertEquals(4, back.getItem(PocketBrewStand.FUEL_SLOT).getCount());
        assertTrue(back.getItem(PocketBrewStand.FIRST_BOTTLE + 1).is(Items.GLASS_BOTTLE));
    }

    @Test
    void upgradeRaisesEveryEffectAndKeepsThePotion() {
        ItemStack strength = PotionContents.createItemStack(Items.POTION, Potions.STRENGTH);
        ItemStack up = BrewmasterMath.upgrade(strength, 1);
        PotionContents c = up.get(DataComponents.POTION_CONTENTS);
        assertTrue(c.potion().isPresent());
        assertEquals(1, c.customEffects().size());
        assertEquals(1, c.customEffects().get(0).getAmplifier());
        assertSame(strength, BrewmasterMath.upgrade(strength, 0));
        ItemStack water = PotionContents.createItemStack(Items.POTION, Potions.WATER);
        assertSame(water, BrewmasterMath.upgrade(water, 2));
    }

    @Test
    void mergeKeepsTheStrongestCopy() {
        MobEffectInstance weak = new MobEffectInstance(MobEffects.DAMAGE_BOOST, 3600, 0);
        MobEffectInstance strong = new MobEffectInstance(MobEffects.DAMAGE_BOOST, 3600, 1);
        MobEffectInstance speed = new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 100, 0);
        List<MobEffectInstance> merged = BrewmasterMath.merge(List.of(weak, speed, strong));
        assertEquals(2, merged.size());
        assertEquals(1, merged.get(0).getAmplifier());
        assertEquals(MobEffects.MOVEMENT_SPEED, merged.get(1).getEffect());
        List<MobEffectInstance> plain = List.of(weak, speed);
        assertSame(plain, BrewmasterMath.merge(plain));
    }

    @Test
    void theStampDoesNotMakeAPotionDifferent() {
        ItemStack stamped = PotionContents.createItemStack(Items.POTION, Potions.STRENGTH);
        PotionStacking.conform(stamped, 3);
        ItemStack fresh = PotionContents.createItemStack(Items.POTION, Potions.STRENGTH);
        assertTrue(PotionStacking.sameIgnoringStamp(stamped, fresh));
        assertFalse(PotionStacking.sameIgnoringStamp(stamped, PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS)));
        assertFalse(PotionStacking.sameIgnoringStamp(stamped, BrewmasterMath.upgrade(fresh, 1)));
    }

    @Test
    void potionsStampAndStrip() {
        ItemStack potion = new ItemStack(Items.POTION);
        assertTrue(PotionStacking.conform(potion, 3));
        assertEquals(4, potion.getMaxStackSize());
        assertFalse(PotionStacking.conform(potion, 3));
        potion.setCount(3);
        PotionStacking.strip(potion);
        assertEquals(3, potion.getMaxStackSize());
        potion.setCount(1);
        PotionStacking.strip(potion);
        assertEquals(1, potion.getMaxStackSize());
        assertFalse(PotionStacking.conform(new ItemStack(Items.GLASS_BOTTLE), 5));
    }
}
