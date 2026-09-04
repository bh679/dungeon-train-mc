package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlacedCarriageFacts} is what stops the debug panel reporting a carriage that isn't there.
 * The panel used to re-roll the pick on demand, which drifts once the train moves — so the
 * behaviour worth pinning is that an index nobody placed reads as <em>unknown</em> rather than
 * being answered confidently.
 */
final class PlacedCarriageFactsTest {

    @BeforeEach
    void reset() {
        PlacedCarriageFacts.clear();
    }

    @Test
    @DisplayName("an index this session never placed is unknown, not guessed")
    void unplacedIndex_isNull() {
        assertNull(PlacedCarriageFacts.get(7));
    }

    @Test
    @DisplayName("a relay-leased slot records its variant and says so instead of inventing a roll")
    void relayBuild_recordsVariantOnly() {
        CarriageVariant variant = CarriageVariant.of(CarriagePlacer.CarriageType.STANDARD);

        PlacedCarriageFacts.recordRelayBuild(3, variant);
        PlacedCarriageFacts.Facts facts = PlacedCarriageFacts.get(3);

        assertNotNull(facts);
        assertEquals(variant.id(), facts.variantId());
        assertEquals(PlacedCarriageFacts.RELAY_BUILD, facts.contentsId());
        assertTrue(facts.subVariantId().isEmpty(), "a verbatim stamp has no sub-variant to report");
    }

    @Test
    @DisplayName("clear forgets everything — a regenerated train must not report the old one")
    void clear_forgetsPriorTrain() {
        PlacedCarriageFacts.recordRelayBuild(1, CarriageVariant.of(CarriagePlacer.CarriageType.STANDARD));

        PlacedCarriageFacts.clear();

        assertNull(PlacedCarriageFacts.get(1));
    }

    @Test
    @DisplayName("null ids normalise to empty so the panel shows a dash, never a literal null")
    void nullFields_normalise() {
        PlacedCarriageFacts.Facts facts = new PlacedCarriageFacts.Facts(null, null, null);

        assertEquals("", facts.variantId());
        assertEquals("", facts.contentsId());
        assertEquals("", facts.subVariantId());
    }

    @Test
    @DisplayName("a relay build reports no flip — it is stamped verbatim, never rolled")
    void relayBuild_recordsNoFlip() {
        PlacedCarriageFacts.recordRelayBuild(7, CarriageVariant.of(CarriagePlacer.CarriageType.STANDARD));

        assertEquals(ContentsFlip.LABEL_NONE, PlacedCarriageFacts.get(7).flip());
    }

    @Test
    @DisplayName("the flip label round-trips onto the facts the F3+4 panel reads")
    void flip_roundTrips() {
        PlacedCarriageFacts.Facts facts = new PlacedCarriageFacts.Facts("std", "maze", "", "X+Z");
        assertEquals("X+Z", facts.flip());
        // The back-compat 3-arg form is an unknown flip, which the panel renders as its dash.
        assertEquals("", new PlacedCarriageFacts.Facts("std", "maze", "").flip());
        assertEquals("", new PlacedCarriageFacts.Facts(null, null, null, null).flip());
    }

    @Test
    @DisplayName("negative carriage indices are kept distinct from their positive twins")
    void negativeIndices_areDistinct() {
        PlacedCarriageFacts.recordRelayBuild(-4, CarriageVariant.of(CarriagePlacer.CarriageType.STANDARD));

        assertNotNull(PlacedCarriageFacts.get(-4));
        assertNull(PlacedCarriageFacts.get(4));
    }
}
