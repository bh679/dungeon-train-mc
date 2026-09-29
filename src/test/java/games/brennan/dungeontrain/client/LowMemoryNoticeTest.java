package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.version.LauncherDetector.Launcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LowMemoryNoticeTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    @DisplayName("Launcher-default 4 GB on a 16 GB machine warns")
    void defaultHeapOnRoomyMachine_warns() {
        assertTrue(LowMemoryNotice.shouldWarn(4 * GIB, 16 * GIB));
    }

    @Test
    @DisplayName("6 GB or more is quiet")
    void recommendedHeap_isQuiet() {
        assertFalse(LowMemoryNotice.shouldWarn(6 * GIB, 16 * GIB));
        assertFalse(LowMemoryNotice.shouldWarn(12 * GIB, 32 * GIB));
    }

    @Test
    @DisplayName("A small machine has nothing spare to give, so no nag")
    void smallMachine_isQuiet() {
        assertFalse(LowMemoryNotice.shouldWarn(2 * GIB, 4 * GIB));
        assertFalse(LowMemoryNotice.shouldWarn(4 * GIB, 6 * GIB));
    }

    @Test
    @DisplayName("An unreadable reading is unknown, never low")
    void unknownReadings_areQuiet() {
        assertFalse(LowMemoryNotice.shouldWarn(4 * GIB, 0));
        assertFalse(LowMemoryNotice.shouldWarn(0, 16 * GIB));
        assertFalse(LowMemoryNotice.shouldWarn(-1, -1));
    }

    @Test
    @DisplayName("Heap boundary: just under 5 GiB warns, exactly 5 GiB does not")
    void heapBoundary() {
        assertTrue(LowMemoryNotice.shouldWarn(5 * GIB - 1, 16 * GIB));
        assertFalse(LowMemoryNotice.shouldWarn(5 * GIB, 16 * GIB));
    }

    @Test
    @DisplayName("RAM boundary: an '8 GB' machine reporting 7.5 GiB counts, just under does not")
    void physicalBoundary() {
        assertTrue(LowMemoryNotice.shouldWarn(4 * GIB, 15 * GIB / 2));
        assertTrue(LowMemoryNotice.shouldWarn(4 * GIB, (long) (7.8 * GIB)));
        assertFalse(LowMemoryNotice.shouldWarn(4 * GIB, 15 * GIB / 2 - 1));
    }

    @Test
    @DisplayName("Each launcher links to its own wiki page; an unknown one gets the hub")
    void howToUrl_perLauncher() {
        String base = "https://github.com/bh679/dungeon-train-mc/wiki/Memory";
        assertEquals(base + "-CurseForge", LowMemoryNotice.howToUrl(Launcher.CURSEFORGE));
        assertEquals(base + "-Modrinth-App", LowMemoryNotice.howToUrl(Launcher.MODRINTH));
        assertEquals(base + "-Minecraft-Launcher", LowMemoryNotice.howToUrl(Launcher.MINECRAFT_LAUNCHER));
        assertEquals(base + "-Prism-Launcher", LowMemoryNotice.howToUrl(Launcher.PRISM));
        assertEquals(base + "-MultiMC", LowMemoryNotice.howToUrl(Launcher.MULTIMC));
        assertEquals(base + "-ATLauncher", LowMemoryNotice.howToUrl(Launcher.ATLAUNCHER));
        assertEquals(base, LowMemoryNotice.howToUrl(Launcher.UNKNOWN));
    }

    @Test
    @DisplayName("Heap is shown as the player would say it")
    void formatGb() {
        assertEquals("4", LowMemoryNotice.formatGb(4 * GIB));
        assertEquals("3.5", LowMemoryNotice.formatGb(7 * GIB / 2));
        assertEquals("3.8", LowMemoryNotice.formatGb((long) (3.8 * GIB)));
    }
}
