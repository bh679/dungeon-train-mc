package games.brennan.dungeontrain.client.version;

import games.brennan.dungeontrain.client.version.LauncherDetector.Launcher;
import games.brennan.dungeontrain.client.version.UpdateHelpScreen.Variant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpdateHelpScreenTest {

    @Test
    @DisplayName("Launchers with their own Update button get pointed at it, modpack or bare mod")
    void managedLaunchers() {
        for (Launcher l : new Launcher[] {Launcher.CURSEFORGE, Launcher.MODRINTH, Launcher.PRISM, Launcher.ATLAUNCHER}) {
            assertEquals(Variant.PACK_MANAGED, UpdateHelpScreen.variant(l, true), l.name());
            assertEquals(Variant.MOD_MANAGED, UpdateHelpScreen.variant(l, false), l.name());
            assertNotNull(UpdateHelpScreen.launcherName(l), l.name());
        }
    }

    @Test
    @DisplayName("PCL, HMCL (unknown), the official launcher and MultiMC swap the jar by hand")
    void manualLaunchers() {
        for (Launcher l : new Launcher[] {Launcher.UNKNOWN, Launcher.MINECRAFT_LAUNCHER, Launcher.MULTIMC}) {
            assertEquals(Variant.PACK_MANUAL, UpdateHelpScreen.variant(l, true), l.name());
            assertEquals(Variant.MOD_MANUAL, UpdateHelpScreen.variant(l, false), l.name());
            assertNull(UpdateHelpScreen.launcherName(l), l.name());
        }
    }

    @Test
    @DisplayName("Every launcher lands in some variant, and only the manual ones get Download")
    void everyLauncherCovered() {
        for (Launcher l : Launcher.values()) {
            for (boolean modpack : new boolean[] {true, false}) {
                Variant v = UpdateHelpScreen.variant(l, modpack);
                assertEquals(UpdateHelpScreen.launcherName(l) == null, v.manual(), l + " modpack=" + modpack);
                assertFalse(UpdateHelpScreen.paragraphs(v, "X").isEmpty());
            }
        }
    }
}
