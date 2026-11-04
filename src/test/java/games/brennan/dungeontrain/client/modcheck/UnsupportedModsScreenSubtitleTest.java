package games.brennan.dungeontrain.client.modcheck;

import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** {@link UnsupportedModsScreen#unverifiedSubtitle}: Essential gets "update it", any other file its name. */
class UnsupportedModsScreenSubtitleTest {

    private static TranslatableContents contents(UnsupportedModsScreen.UnsupportedMod mod) {
        return assertInstanceOf(TranslatableContents.class, UnsupportedModsScreen.unverifiedSubtitle(mod).getContents());
    }

    @Test
    @DisplayName("An unverified Essential is told to update; other unverified files are named")
    void essentialIsToldToUpdate() {
        TranslatableContents ess = contents(new UnsupportedModsScreen.UnsupportedMod("essential", "Essential", "Essential (neoforge_1.21.1).jar"));
        assertEquals("gui.dungeontrain.unsupported_mods.update_essential", ess.getKey());
        TranslatableContents other = contents(new UnsupportedModsScreen.UnsupportedMod("jei", "JEI", "jei-19.jar"));
        assertEquals("gui.dungeontrain.unsupported_mods.unverified_file", other.getKey());
        assertArrayEquals(new Object[] {"jei-19.jar"}, other.getArgs());
        assertTrue(UnsupportedModsScreen.isUpdateRow(new UnsupportedModsScreen.UnsupportedMod("essential", "Essential", "e.jar")), "blue update line");
        assertFalse(UnsupportedModsScreen.isUpdateRow(new UnsupportedModsScreen.UnsupportedMod("essential", "Essential")), "unapproved id, not an old file");
        assertFalse(UnsupportedModsScreen.isUpdateRow(new UnsupportedModsScreen.UnsupportedMod("jei", "JEI", "jei.jar")));
    }

    @Test
    @DisplayName("A namespace-listed id's unverified file is told to install from a store")
    void namespaceListedIsToldToInstallFromStore() {
        java.util.function.Predicate<String> listed = "customskinloader"::equals;
        UnsupportedModsScreen.UnsupportedMod csl =
            new UnsupportedModsScreen.UnsupportedMod("customskinloader", "CustomSkinLoader", "CustomSkinLoader-Common.jar");
        assertEquals(UnsupportedModsScreen.Hint.INSTALL_FROM_STORE, UnsupportedModsScreen.hintOf(csl, listed));
        assertEquals(UnsupportedModsScreen.Hint.UPDATE_ESSENTIAL, UnsupportedModsScreen.hintOf(
            new UnsupportedModsScreen.UnsupportedMod("essential", "Essential", "e.jar"), listed));
        assertEquals(UnsupportedModsScreen.Hint.NONE, UnsupportedModsScreen.hintOf(
            new UnsupportedModsScreen.UnsupportedMod("jei", "JEI", "jei.jar"), listed));
        assertEquals(UnsupportedModsScreen.Hint.NONE, UnsupportedModsScreen.hintOf(
            new UnsupportedModsScreen.UnsupportedMod("customskinloader", "CustomSkinLoader"), listed),
            "unapproved id, not an unverified file");
        // Live path: the baked list carries customskinloader, so the real subtitle is the store hint.
        assertEquals("gui.dungeontrain.unsupported_mods.install_from_store", contents(csl).getKey());
        assertFalse(UnsupportedModsScreen.isUpdateRow(csl), "store hint, not the Essential update");
    }
}
