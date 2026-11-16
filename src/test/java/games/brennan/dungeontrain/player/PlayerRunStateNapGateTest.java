package games.brennan.dungeontrain.player;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** After a nap, count-based counters grow only ahead of the nap point and shrink behind it. */
final class PlayerRunStateNapGateTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("no nap: every step counts up")
    void ungatedAlwaysUp() {
        PlayerRunState s = new PlayerRunState();
        assertFalse(s.napGated());
        assertEquals(1, s.counterStep(-1_000_000));
        assertEquals(1, s.counterStep(1_000_000));
    }

    @Test
    @DisplayName("ahead counts up, behind counts down, for either travel direction")
    void stepByDirection() {
        PlayerRunState plus = new PlayerRunState();
        plus.setNapGate(100, 1);
        assertEquals(1, plus.counterStep(100));
        assertEquals(1, plus.counterStep(150));
        assertEquals(-1, plus.counterStep(99));

        PlayerRunState minus = new PlayerRunState();
        minus.setNapGate(100, -1);
        assertEquals(1, minus.counterStep(50));
        assertEquals(-1, minus.counterStep(101));
    }

    @Test
    @DisplayName("travel direction is the sign of net carriages, facing only when that is 0")
    void travelDirection() {
        assertEquals(1, PlayerRunState.travelDirection(12, -1));
        assertEquals(-1, PlayerRunState.travelDirection(-3, 1));
        assertEquals(-1, PlayerRunState.travelDirection(0, -1));
        assertEquals(1, PlayerRunState.travelDirection(0, 1));
    }

    @Test
    @DisplayName("a later nap only moves the gate further ahead; a reversed one replaces it")
    void chainedNaps() {
        PlayerRunState s = new PlayerRunState();
        s.setNapGate(500, 1);
        s.setNapGate(300, 1);
        assertEquals(500, s.napGateX());
        s.setNapGate(800, 1);
        assertEquals(800, s.napGateX());
        s.setNapGate(200, -1);
        assertEquals(200, s.napGateX());
        assertEquals(-1, s.napGateDir());
    }

    @Test
    @DisplayName("counters step down behind, never below zero")
    void countersShrinkClamped() {
        PlayerRunState s = new PlayerRunState();
        s.incrementMobKills(1);
        s.incrementMobKills(1);
        assertEquals(1, s.incrementMobKills(-1));
        assertEquals(0, s.incrementMobKills(-1));
        assertEquals(0, s.incrementMobKills(-1));
        assertEquals(0, s.incrementEchoesKilled(-1));
        assertEquals(0, s.incrementPlayerKills(-1));
        assertEquals(0, s.incrementContainersOpened(-1));
        assertEquals(0, s.incrementBooksRead(-1));
        s.recordPhotoTaken(true, true, true, 1);
        s.recordPhotoTaken(true, false, false, -1);
        assertEquals(0, s.photosTaken());
        assertEquals(0, s.photosPassengers());
        assertEquals(1, s.photosHostile());
        s.incrementPhotoShots(-1);
        assertEquals(0, s.photoShots());
    }

    @Test
    @DisplayName("weapon kills step down and drop a weapon at zero")
    void weaponKillsShrink() {
        PlayerRunState s = new PlayerRunState();
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        s.recordWeaponKill(sword, 1);
        s.recordWeaponKill(sword, -1);
        assertTrue(s.weaponKillsSnapshot().isEmpty());
    }

    @Test
    @DisplayName("tames step down, taking the latest name with them")
    void tamesShrink() {
        PlayerRunState s = new PlayerRunState();
        s.recordTame(ResourceLocation.parse("minecraft:wolf"), 1);
        s.recordTame(ResourceLocation.parse("minecraft:cat"), 1);
        assertEquals(1, s.recordTame(ResourceLocation.parse("minecraft:cat"), -1));
        assertEquals(1, s.tamedAnimals().size());
        assertEquals(0, s.recordTame(ResourceLocation.parse("minecraft:cat"), -1));
        assertEquals(0, s.recordTame(ResourceLocation.parse("minecraft:cat"), -1));
    }

    @Test
    @DisplayName("chest streak shrinks one at a time, carried stand-ins first")
    void chestStreakShrinks() {
        PlayerRunState s = new PlayerRunState();
        s.addChestPos(new BlockPos(1, 64, 1));
        s.forgetChestPositionsKeepingCount();
        s.addChestPos(new BlockPos(2, 64, 2));
        s.shrinkChestStreak();
        assertEquals(1, s.chestStreak());
        assertEquals(0, s.uniqueChestsCarried());
        s.shrinkChestStreak();
        s.shrinkChestStreak();
        assertEquals(0, s.chestStreak());
    }

    @Test
    @DisplayName("the gate rides the nap extras and ends with the life")
    void gateCarriedAndReset() {
        PlayerRunState s = new PlayerRunState();
        s.setNapGate(-640.5, -1);
        CompoundTag extras = s.napExtras(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        PlayerRunState woken = new PlayerRunState();
        woken.applyNapExtras(extras, null);
        assertEquals(-640.5, woken.napGateX());
        assertEquals(-1, woken.napGateDir());
        woken.resetAll();
        assertFalse(woken.napGated());
    }
}
