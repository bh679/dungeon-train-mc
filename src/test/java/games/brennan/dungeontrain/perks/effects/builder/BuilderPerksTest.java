package games.brennan.dungeontrain.perks.effects.builder;

import games.brennan.dungeontrain.perks.PerkClass;
import games.brennan.dungeontrain.perks.PerkRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two Builder tier 2 ids are known to the registry once the effects class loads. */
class BuilderPerksTest {

    @Test
    void registersBothTierTwoPerksUnderBuilder() {
        BuilderPerks.ensureRegistered();
        for (String id : new String[] {OwnCarriageReturn.ID, DriftGiftPayout.ID}) {
            assertTrue(PerkRegistry.isKnown(id), id);
            assertEquals(PerkClass.BUILDER, PerkRegistry.get(id).orElseThrow().perkClass(), id);
            assertEquals(2, PerkRegistry.get(id).orElseThrow().tier(), id);
        }
    }
}
