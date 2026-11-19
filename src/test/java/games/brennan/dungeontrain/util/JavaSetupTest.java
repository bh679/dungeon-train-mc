package games.brennan.dungeontrain.util;

import games.brennan.dungeontrain.util.JavaSetup.Manager;
import games.brennan.dungeontrain.util.JavaSetup.Vendor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaSetupTest {

    @Test
    @DisplayName("Minecraft Launcher runtimes, Store and classic installs")
    void minecraftLauncher() {
        assertEquals(Manager.MINECRAFT_LAUNCHER, JavaSetup.manager(
            "C:\\Users\\a\\AppData\\Local\\Packages\\Microsoft.4297127D64EC6_8wekyb3d8bbwe\\LocalCache\\Local\\runtime\\java-runtime-delta\\windows-x64\\java-runtime-delta"));
        assertEquals(Manager.MINECRAFT_LAUNCHER, JavaSetup.manager(
            "C:\\Users\\a\\AppData\\Roaming\\.minecraft\\runtime\\java-runtime-delta\\windows-x64\\java-runtime-delta"));
        assertEquals(Manager.MINECRAFT_LAUNCHER, JavaSetup.manager(
            "/home/a/.minecraft/runtime/java-runtime-delta/linux/java-runtime-delta"));
    }

    @Test
    @DisplayName("Launchers that keep Mojang's runtime in their own folder win over the folder name")
    void launcherFoldersFirst() {
        assertEquals(Manager.CURSEFORGE, JavaSetup.manager(
            "C:\\Users\\a\\curseforge\\minecraft\\Install\\runtime\\java-runtime-delta\\windows-x64\\java-runtime-delta"));
        assertEquals(Manager.PRISM, JavaSetup.manager(
            "C:\\Users\\a\\AppData\\Roaming\\PrismLauncher\\java\\java-runtime-delta"));
        assertEquals(Manager.ATLAUNCHER, JavaSetup.manager(
            "C:\\ATLauncher\\runtimes\\minecraft\\java-runtime-delta\\windows-x64\\java-runtime-delta"));
    }

    @Test
    @DisplayName("Modrinth App's downloaded Java")
    void modrinth() {
        assertEquals(Manager.MODRINTH, JavaSetup.manager(
            "C:\\Users\\a\\AppData\\Roaming\\ModrinthApp\\meta\\java_versions\\zulu21.40.17-ca-jre21.0.6-win_x64"));
        assertEquals(Manager.MODRINTH, JavaSetup.manager(
            "/home/a/.local/share/com.modrinth.theseus/meta/java_versions/zulu21"));
    }

    @Test
    @DisplayName("A Java the player installed")
    void ownJava() {
        assertEquals(Manager.OWN, JavaSetup.manager("C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.13.9-hotspot"));
        assertEquals(Manager.OWN, JavaSetup.manager("/usr/lib/jvm/java-21-openjdk-amd64"));
        assertEquals(Manager.OWN, JavaSetup.manager(null));
    }

    @Test
    @DisplayName("Vendors")
    void vendors() {
        assertEquals(Vendor.ADOPTIUM, JavaSetup.vendor("Eclipse Adoptium Eclipse Adoptium"));
        assertEquals(Vendor.MICROSOFT, JavaSetup.vendor("Microsoft Microsoft"));
        assertEquals(Vendor.ORACLE, JavaSetup.vendor("Oracle Corporation Oracle Corporation"));
        assertEquals(Vendor.AZUL, JavaSetup.vendor("Azul Systems, Inc. Azul Systems, Inc."));
        assertEquals(Vendor.AMAZON, JavaSetup.vendor("Amazon.com Inc. Amazon.com Inc."));
        assertEquals(Vendor.OTHER, JavaSetup.vendor("Private Build Private Build"));
        assertEquals(Vendor.OTHER, JavaSetup.vendor(null));
    }

    @Test
    @DisplayName("Temurin's page opens on the player's Java version")
    void downloadUrl() {
        assertEquals("https://adoptium.net/temurin/releases/?version=21", Vendor.ADOPTIUM.downloadUrl(21));
        assertEquals("https://learn.microsoft.com/java/openjdk/download", Vendor.MICROSOFT.downloadUrl(21));
        assertNotNull(JavaSetup.current());
    }
}
