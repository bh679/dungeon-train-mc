package games.brennan.dungeontrain.nap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NapGuestsTest {

    private static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @TempDir Path dir;

    @Test void hostConnectionAloneIsNotAGuest() {
        assertFalse(NapGuests.othersConnected(0));
        assertFalse(NapGuests.othersConnected(1));
    }

    @Test void anySecondConnectionIsAGuest() {
        assertTrue(NapGuests.othersConnected(2));
        assertTrue(NapGuests.othersConnected(5));
    }

    @Test void missingFolderHasNoGuests() {
        assertFalse(NapGuests.othersSaved(dir.resolve("playerdata"), HOST));
    }

    @Test void emptyFolderHasNoGuests() {
        assertFalse(NapGuests.othersSaved(dir, HOST));
    }

    @Test void hostOnlyHasNoGuests() throws Exception {
        Files.writeString(dir.resolve(HOST + ".dat"), "x");
        Files.writeString(dir.resolve(HOST + ".dat_old"), "x");
        assertFalse(NapGuests.othersSaved(dir, HOST));
    }

    @Test void guestFileCounts() throws Exception {
        Files.writeString(dir.resolve(HOST + ".dat"), "x");
        Files.writeString(dir.resolve(GUEST + ".dat"), "x");
        assertTrue(NapGuests.othersSaved(dir, HOST));
    }

    @Test void guestBackupAloneDoesNotCount() throws Exception {
        Files.writeString(dir.resolve(GUEST + ".dat_old"), "x");
        assertFalse(NapGuests.othersSaved(dir, HOST));
    }

    @Test void junkNamesAreIgnored() throws Exception {
        Files.writeString(dir.resolve("notes.dat"), "x");
        Files.writeString(dir.resolve("readme.txt"), "x");
        assertFalse(NapGuests.othersSaved(dir, HOST));
    }
}
