package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which stored potions a loot entry randomises: none / water / mundane / thick / awkward roll
 * a random potion; anything with a real effect is placed exactly as authored.
 */
final class ContainerContentsPotionsRuleTest {

    private static ResourceLocation mc(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    @Test
    void basesAndEmpty_randomise() {
        assertTrue(ContainerContentsPotions.isRandomisedBase(null));
        assertTrue(ContainerContentsPotions.isRandomisedBase(mc("water")));
        assertTrue(ContainerContentsPotions.isRandomisedBase(mc("mundane")));
        assertTrue(ContainerContentsPotions.isRandomisedBase(mc("thick")));
        assertTrue(ContainerContentsPotions.isRandomisedBase(mc("awkward")));
    }

    @Test
    void effectPotions_keep() {
        assertFalse(ContainerContentsPotions.isRandomisedBase(mc("healing")));
        assertFalse(ContainerContentsPotions.isRandomisedBase(mc("poison")));
        assertFalse(ContainerContentsPotions.isRandomisedBase(mc("long_night_vision")));
        assertFalse(ContainerContentsPotions.isRandomisedBase(mc("strong_strength")));
        assertFalse(ContainerContentsPotions.isRandomisedBase(
            ResourceLocation.fromNamespaceAndPath("somemod", "water")));
    }
}
