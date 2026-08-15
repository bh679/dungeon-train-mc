package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageWeights;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of the two corridor shapes a pair draws — {@link PortalCarriageSelection#corridorKindFor}.
 *
 * <p>The draw runs over the two corridor <b>variants</b> and their entries in
 * {@code templates/weights.json}, so the numbers an author sets there are the mix on the train. The
 * three properties worth pinning are that the shipped 1:1 really is a coin flip rather than a
 * near-constant, that a pair's answer never moves for a given key, and that zeroing one kind turns
 * it off completely rather than merely making it rare.</p>
 */
final class PortalCorridorKindDrawTest {

    /**
     * {@link PortalCarriageBuilder} holds {@code Blocks.*} constants in static fields, and naming
     * the corridor variants touches it. Same pattern as {@code BundledCarriageAndContentsWeightsTest}.
     */
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final long SEED = 0x5EEDL;

    /** How many pair keys each proportion is measured over. */
    private static final int SAMPLE = 4000;

    private static CarriageWeights weights(int longWeight, int shortWeight) {
        return CarriageWeights.ofWeights(Map.of(
            PortalCarriageBuilder.portalVariant(PortalCorridorKind.LONG).id(), longWeight,
            PortalCarriageBuilder.portalVariant(PortalCorridorKind.SHORT).id(), shortWeight));
    }

    private static int shortCount(CarriageWeights w) {
        int shorts = 0;
        for (int pairKey = 0; pairKey < SAMPLE; pairKey++) {
            PortalCorridorKind kind = PortalCarriageSelection.corridorKindFor(pairKey, SEED, w);
            assertNotNull(kind, "pairKey " + pairKey + " drew nothing");
            if (kind == PortalCorridorKind.SHORT) shorts++;
        }
        return shorts;
    }

    @Test
    @DisplayName("equal weights are a coin flip — the shipped 50/50")
    void equalWeights_giveHalfEach() {
        int shorts = shortCount(weights(1, 1));
        // Generous band: this is asserting "a fair-ish coin", not a particular RNG stream. A broken
        // draw — keyed on nothing, or reading one weight twice — lands at 0 or SAMPLE, not near half.
        assertTrue(shorts > SAMPLE * 0.44 && shorts < SAMPLE * 0.56,
            "expected roughly half of " + SAMPLE + " pairs short, got " + shorts);
    }

    @Test
    @DisplayName("a pair's kind is the same answer every time it is asked")
    void theDrawIsStableForAKey() {
        CarriageWeights w = weights(1, 1);
        for (int pairKey = -50; pairKey < 50; pairKey++) {
            PortalCorridorKind first = PortalCarriageSelection.corridorKindFor(pairKey, SEED, w);
            for (int repeat = 0; repeat < 5; repeat++) {
                assertEquals(first, PortalCarriageSelection.corridorKindFor(pairKey, SEED, w),
                    "pairKey " + pairKey + " must draw the same kind on every reload — a corridor "
                        + "that changed length under a standing player would move the swap plane");
            }
        }
    }

    @Test
    @DisplayName("weighting one kind 0 turns it off outright")
    void zeroWeight_removesAKind() {
        assertEquals(0, shortCount(weights(1, 0)), "portal_short at 0 must never be drawn");
        assertEquals(SAMPLE, shortCount(weights(0, 1)), "portal at 0 must leave only short");
    }

    @Test
    @DisplayName("a lopsided weighting shifts the mix in that direction")
    void weightsSetTheMix() {
        int shorts = shortCount(weights(3, 1));
        assertTrue(shorts > SAMPLE * 0.19 && shorts < SAMPLE * 0.31,
            "expected roughly a quarter of " + SAMPLE + " pairs short at 3:1, got " + shorts);
    }

    @Test
    @DisplayName("both weights 0 still resolves to a shape rather than to nothing")
    void allZero_fallsBackRatherThanFailing() {
        // weightedSeededPick falls back to an unweighted pick over the pool when every weight is 0,
        // so this cannot return null — but it can return either kind, and the only thing worth
        // asserting is that it answers at all.
        for (int pairKey = 0; pairKey < 100; pairKey++) {
            assertNotNull(PortalCarriageSelection.corridorKindFor(pairKey, SEED, weights(0, 0)));
        }
    }

    @Test
    @DisplayName("every portal template is excluded from the ordinary carriage pool by name")
    void portalVariantsAreNotOrdinaryCarriages() {
        for (PortalCorridorKind kind : PortalCorridorKind.values()) {
            assertTrue(PortalCarriageBuilder.isPortalVariant(
                    PortalCarriageBuilder.portalVariant(kind)),
                kind + "'s corridor must be filtered out of CarriagePlacer's pool. It used to be "
                    + "kept out by a weight of 0, which LOOPING mode ignores — and the weights now "
                    + "carry the split between the kinds, so they can no longer be 0.");
        }
        assertTrue(PortalCarriageBuilder.isPortalVariant(PortalCarriageBuilder.middleVariant()));
        assertTrue(PortalCarriageBuilder.isPortalVariant(
            games.brennan.dungeontrain.train.CarriageVariant.of(
                games.brennan.dungeontrain.train.CarriagePlacer.CarriageType.STANDARD)) == false,
            "an ordinary carriage must not be mistaken for a portal template");
    }
}
