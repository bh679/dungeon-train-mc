package games.brennan.dungeontrain.perks;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One set of perk unlocks per difficulty: what a login loads, and how the save keeps them apart. */
class PerkProfilesTest {

    private static final Map<String, Integer> SAVED = Map.of("health", 3);
    private static final Map<String, Integer> WORLD = Map.of("health", 1, "speed", 2);

    @Test
    @DisplayName("an honest login takes the save, never the world's copy")
    void saveWins() {
        assertEquals(SAVED, PerkProfiles.loginUnlocks(true, SAVED, WORLD, false));
    }

    @Test
    @DisplayName("a difficulty with nothing saved starts empty, even when another difficulty has a save")
    void emptyProfile() {
        assertEquals(Map.of(), PerkProfiles.loginUnlocks(true, Map.of(), WORLD, false));
    }

    @Test
    @DisplayName("Free Play keeps this world's purchases on top of the save")
    void freePlayMerges() {
        assertEquals(Map.of("health", 3, "speed", 2), PerkProfiles.loginUnlocks(true, SAVED, WORLD, true));
    }

    @Test
    @DisplayName("a world from before the save is adopted once, and never from Free Play")
    void adoption() {
        assertEquals(WORLD, PerkProfiles.loginUnlocks(false, Map.of(), WORLD, false));
        assertTrue(PerkProfiles.adoptsWorldCopy(false, WORLD, false));
        assertFalse(PerkProfiles.adoptsWorldCopy(true, WORLD, false));
        assertFalse(PerkProfiles.adoptsWorldCopy(false, WORLD, true));
        assertFalse(PerkProfiles.adoptsWorldCopy(false, Map.of(), false));
    }

    @Test
    @DisplayName("active points are capped at the new unlock level, and dropped where locked")
    void clampsActive() {
        PerkState state = PerkState.of(Map.of("health", 2), Map.of("health", 5, "speed", 1));
        assertEquals(2, state.activePoints("health"));
        assertEquals(0, state.activePoints("speed"));
    }

    @Test
    @DisplayName("each difficulty's unlocks are saved apart")
    void partitionsApart() {
        CompoundTag root = PerkProfileStore.withUnlockLevel(new CompoundTag(), "normal", "health", 3);
        root = PerkProfileStore.withUnlockLevel(root, "hard", "speed", 1);
        assertEquals(Map.of("health", 3), PerkProfileStore.unlocksIn(root, "normal"));
        assertEquals(Map.of("speed", 1), PerkProfileStore.unlocksIn(root, "hard"));
        assertEquals(Map.of(), PerkProfileStore.unlocksIn(root, "easy"));
    }

    @Test
    @DisplayName("a level of zero removes the perk from that difficulty only")
    void levelZeroRemoves() {
        CompoundTag root = PerkProfileStore.withUnlocks(new CompoundTag(), "normal", Map.of("health", 3, "speed", 1));
        root = PerkProfileStore.withUnlockLevel(root, "normal", "health", 0);
        assertEquals(Map.of("speed", 1), PerkProfileStore.unlocksIn(root, "normal"));
    }

    @Test
    @DisplayName("parking keeps the unlocks, and writes never change the tag they were given")
    void parkingKeepsUnlocks() {
        CompoundTag before = PerkProfileStore.withUnlockLevel(new CompoundTag(), "normal", "health", 3);
        CompoundTag parked = PerkProfileStore.withParked(before, "normal",
            new PerkProfileStore.Parked(Map.of("health", 2), 4));
        assertEquals(Map.of("health", 3), PerkProfileStore.unlocksIn(parked, "normal"));
        assertEquals(new PerkProfileStore.Parked(Map.of("health", 2), 4), PerkProfileStore.parkedIn(parked, "normal"));
        assertEquals(PerkProfileStore.Parked.NONE, PerkProfileStore.parkedIn(before, "normal"));
    }

    @Test
    @DisplayName("parked ranks come back once: taking them leaves nothing behind")
    void parkedTakenOnce() {
        CompoundTag root = PerkProfileStore.withParked(new CompoundTag(), "normal",
            new PerkProfileStore.Parked(Map.of("health", 2), 4));
        CompoundTag taken = PerkProfileStore.withParked(root, "normal", PerkProfileStore.Parked.NONE);
        assertEquals(PerkProfileStore.Parked.NONE, PerkProfileStore.parkedIn(taken, "normal"));
    }
}
