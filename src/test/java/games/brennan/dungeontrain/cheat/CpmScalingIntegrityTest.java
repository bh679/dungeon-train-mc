package games.brennan.dungeontrain.cheat;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which attribute modifiers mean Customizable Player Models is scaling a player. Pure. */
class CpmScalingIntegrityTest {

    @Test
    @DisplayName("Only CPM's own scaling modifier, and only when it moves the value, counts")
    void onlyCpmModifierThatChangesCounts() {
        ResourceLocation cpm = CpmScalingIntegrity.CPM_MODIFIER_ID;
        assertEquals("cpm:24bba381-9615-4530-8fcf-4fc42393a4b5", cpm.toString(), "the id CPM 0.6.x uses");
        assertTrue(CpmScalingIntegrity.changes(cpm, 3.0), "health x4");
        assertTrue(CpmScalingIntegrity.changes(cpm, -0.5), "half size");
        assertFalse(CpmScalingIntegrity.changes(cpm, 0.0), "scale 1: no change");
        assertFalse(CpmScalingIntegrity.changes(ResourceLocation.fromNamespaceAndPath("minecraft", "sprinting"), 0.3),
            "someone else's modifier");
    }
}
