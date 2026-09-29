package games.brennan.dungeontrain.client.version;

import games.brennan.dungeontrain.client.version.LauncherDetector.Launcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LauncherDetectorTest {

    private static Launcher classify(String path) {
        return LauncherDetector.classify(path, false, false);
    }

    @Test
    @DisplayName("CurseForge instances, on Windows and macOS")
    void curseForge() {
        assertEquals(Launcher.CURSEFORGE,
            classify("C:\\Users\\Sam\\curseforge\\minecraft\\Instances\\Dungeon Train"));
        assertEquals(Launcher.CURSEFORGE,
            classify("/Users/sam/Documents/curseforge/minecraft/Instances/Dungeon Train"));
    }

    @Test
    @DisplayName("Modrinth App profiles, including the old Theseus folder")
    void modrinth() {
        assertEquals(Launcher.MODRINTH,
            classify("C:\\Users\\Sam\\AppData\\Roaming\\ModrinthApp\\profiles\\Dungeon Train"));
        assertEquals(Launcher.MODRINTH,
            classify("/Users/sam/Library/Application Support/com.modrinth.theseus/profiles/Dungeon Train"));
    }

    @Test
    @DisplayName("Prism, MultiMC and ATLauncher are told apart, not lumped together")
    void thirdPartyLaunchers() {
        assertEquals(Launcher.PRISM,
            classify("/home/sam/.local/share/PrismLauncher/instances/Dungeon Train/minecraft"));
        assertEquals(Launcher.MULTIMC,
            classify("C:\\Games\\MultiMC\\instances\\Dungeon Train\\.minecraft"));
        assertEquals(Launcher.ATLAUNCHER,
            classify("C:\\Users\\Sam\\AppData\\Roaming\\ATLauncher\\instances\\DungeonTrain"));
    }

    @Test
    @DisplayName("A MultiMC instance's own .minecraft folder is MultiMC, not the official launcher")
    void brandBeatsDotMinecraft() {
        assertEquals(Launcher.MULTIMC, classify("/opt/MultiMC/instances/DT/.minecraft"));
    }

    @Test
    @DisplayName("The official launcher is recognised by its default game folder on every OS")
    void officialLauncher() {
        assertEquals(Launcher.MINECRAFT_LAUNCHER, classify("C:\\Users\\Sam\\AppData\\Roaming\\.minecraft"));
        assertEquals(Launcher.MINECRAFT_LAUNCHER, classify("/home/sam/.minecraft/"));
        assertEquals(Launcher.MINECRAFT_LAUNCHER,
            classify("/Users/sam/Library/Application Support/minecraft"));
    }

    @Test
    @DisplayName("Signature files identify an instance whose path carries no brand")
    void signatureFiles() {
        assertEquals(Launcher.CURSEFORGE, LauncherDetector.classify("D:\\Games\\DT", true, false));
        assertEquals(Launcher.MODRINTH, LauncherDetector.classify("D:\\Games\\DT", false, true));
    }

    @Test
    @DisplayName("Anything unrecognised is UNKNOWN, never a guess")
    void unknown() {
        assertEquals(Launcher.UNKNOWN, classify("D:\\Games\\DungeonTrainPortable"));
        assertEquals(Launcher.UNKNOWN, classify("/Users/sam/dev/dungeon-train/run"));
    }
}
