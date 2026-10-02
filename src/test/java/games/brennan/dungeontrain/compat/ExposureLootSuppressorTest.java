package games.brennan.dungeontrain.compat;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which loot tables DT drops so Exposure's items stay out of vanilla chests. */
final class ExposureLootSuppressorTest {

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    @Test
    @DisplayName("drops every chest table Exposure injects into vanilla loot")
    void dropsExposureChestTables() {
        for (String chest : new String[] {"simple_dungeon", "abandoned_mineshaft", "shipwreck_map",
                "stronghold_crossing", "village_plains_house"}) {
            assertTrue(ExposureLootSuppressor.isSuppressed(id("exposure", "chests/" + chest)), chest);
        }
    }

    @Test
    @DisplayName("leaves Exposure's block drops and everyone else's chests alone")
    void keepsEverythingElse() {
        assertFalse(ExposureLootSuppressor.isSuppressed(id("exposure", "blocks/lightroom")));
        assertFalse(ExposureLootSuppressor.isSuppressed(id("minecraft", "chests/simple_dungeon")));
        assertFalse(ExposureLootSuppressor.isSuppressed(id("dungeontrain", "chests/exposure")));
        assertFalse(ExposureLootSuppressor.isSuppressed(null));
    }
}
