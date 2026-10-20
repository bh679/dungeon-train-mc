package games.brennan.dungeontrain.event;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link GloomslatePotMobEvents}: the break-time roll, the mob pick and the
 * loot round-trip through persistent data. The spawn and the drop need a live level (Gate 2).
 */
final class GloomslatePotMobEventsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("about one pot in ten wakes up")
    void convertRateIsAboutTenPercent() {
        RandomSource rng = RandomSource.create(42L);
        int hits = 0;
        int rolls = 20_000;
        for (int i = 0; i < rolls; i++) if (GloomslatePotMobEvents.shouldConvert(rng)) hits++;
        double rate = hits / (double) rolls;
        assertTrue(rate > 0.085 && rate < 0.115, "rate " + rate);
    }

    @Test
    @DisplayName("the pick covers all three Deeper Darker pot mobs and nothing else")
    void pickCoversAllThreePotMobs() {
        RandomSource rng = RandomSource.create(7L);
        Set<ResourceLocation> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) seen.add(GloomslatePotMobEvents.pickPotMob(rng));
        assertEquals(Set.of(
            ResourceLocation.fromNamespaceAndPath("deeperdarker", "anger_pot"),
            ResourceLocation.fromNamespaceAndPath("deeperdarker", "fear_pot"),
            ResourceLocation.fromNamespaceAndPath("deeperdarker", "sorrow_pot")), seen);
    }

    @Test
    @DisplayName("stored loot comes back once, then is gone")
    void lootRoundTrip() {
        RegistryAccess registries = RegistryAccess.EMPTY;
        CompoundTag persistent = new CompoundTag();
        GloomslatePotMobEvents.storeLoot(persistent, new ItemStack(Items.DIAMOND, 3), registries);
        assertTrue(persistent.contains(GloomslatePotMobEvents.NBT_POT_LOOT));

        Optional<ItemStack> taken = GloomslatePotMobEvents.takeLoot(persistent, registries);
        assertTrue(taken.isPresent());
        assertTrue(taken.get().is(Items.DIAMOND));
        assertEquals(3, taken.get().getCount());
        assertFalse(persistent.contains(GloomslatePotMobEvents.NBT_POT_LOOT));
        assertTrue(GloomslatePotMobEvents.takeLoot(persistent, registries).isEmpty());
    }

    @Test
    @DisplayName("an empty pot stores nothing")
    void emptyPotStoresNothing() {
        CompoundTag persistent = new CompoundTag();
        GloomslatePotMobEvents.storeLoot(persistent, ItemStack.EMPTY, RegistryAccess.EMPTY);
        assertFalse(persistent.contains(GloomslatePotMobEvents.NBT_POT_LOOT));
        assertTrue(GloomslatePotMobEvents.takeLoot(persistent, RegistryAccess.EMPTY).isEmpty());
    }
}
