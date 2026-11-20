package games.brennan.dungeontrain.perks;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The perks each past life activated: kept in order, capped, and saved beside the unlocks. */
class PerkLifeHistoryTest {

    @Test
    @DisplayName("lives append oldest first; a life with no perks is kept as an empty one")
    void appendsInOrder() {
        PerkLifeHistory h = PerkLifeHistory.EMPTY.withLife(List.of("health", "speed")).withLife(List.of());
        assertEquals(List.of(List.of("health", "speed"), List.of()), h.lives());
    }

    @Test
    @DisplayName("only the newest lives are kept")
    void capped() {
        PerkLifeHistory h = PerkLifeHistory.EMPTY;
        for (int i = 0; i < PerkLifeHistory.MAX_LIVES + 5; i++) h = h.withLife(List.of("p" + i));
        assertEquals(PerkLifeHistory.MAX_LIVES, h.size());
        assertEquals(List.of("p5"), h.lives().get(0));
        assertEquals(List.of("p" + (PerkLifeHistory.MAX_LIVES + 4)), h.lives().get(h.size() - 1));
    }

    @Test
    @DisplayName("blank and repeated ids are dropped")
    void cleaned() {
        List<String> ids = new ArrayList<>(List.of("a", "", "a", "b"));
        ids.add(null);
        assertEquals(List.of(List.of("a", "b")), PerkLifeHistory.EMPTY.withLife(ids).lives());
    }

    @Test
    @DisplayName("NBT round trip, and a missing tag reads as no lives (lives before perks)")
    void nbt() {
        PerkLifeHistory h = PerkLifeHistory.of(List.of(List.of("health"), List.of(), List.of("a", "b")));
        CompoundTag tag = new CompoundTag();
        tag.put("PastLives", h.toTag());
        assertEquals(h, PerkLifeHistory.fromTag(tag, "PastLives"));
        assertTrue(PerkLifeHistory.fromTag(new CompoundTag(), "PastLives").lives().isEmpty());
    }

    @Test
    @DisplayName("the save keeps real lives at the root and a test world's apart, unlocks untouched")
    void storeKeepsListsApart() {
        CompoundTag root = PerkProfileStore.withUnlocks(new CompoundTag(), "perktest", java.util.Map.of("health", 2));
        PerkLifeHistory real = PerkLifeHistory.of(List.of(List.of("speed")));
        PerkLifeHistory test = PerkLifeHistory.of(List.of(List.of("health"), List.of()));
        root = PerkProfileStore.withPastLives(root, null, real);
        root = PerkProfileStore.withPastLives(root, "perktest", test);
        assertEquals(real, PerkProfileStore.pastLivesIn(root, null));
        assertEquals(test, PerkProfileStore.pastLivesIn(root, "perktest"));
        assertEquals(java.util.Map.of("health", 2), PerkProfileStore.unlocksIn(root, "perktest"));
    }
}
