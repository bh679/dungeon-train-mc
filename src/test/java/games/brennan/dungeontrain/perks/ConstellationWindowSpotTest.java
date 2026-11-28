package games.brennan.dungeontrain.perks;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstellationWindowSpotTest {

    @Test
    void staysUntilAPointIsSpent() {
        ConstellationWindowSpot s = ConstellationWindowSpot.at(null, 42L);
        assertFalse(ConstellationWindowSpot.shouldRemove(s, false), "walked out without spending: stays");
        assertFalse(ConstellationWindowSpot.shouldRemove(s, true));
    }

    @Test
    void goesWhenSpentAndOut() {
        ConstellationWindowSpot s = ConstellationWindowSpot.at(UUID.randomUUID(), 42L).withSpent();
        assertFalse(ConstellationWindowSpot.shouldRemove(s, true), "spent but still standing in it: stays");
        assertTrue(ConstellationWindowSpot.shouldRemove(s, false));
    }

    @Test
    void nothingPlacedNothingRemoved() {
        assertFalse(ConstellationWindowSpot.shouldRemove(ConstellationWindowSpot.NONE.withSpent(), false));
    }
}
