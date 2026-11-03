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
}
