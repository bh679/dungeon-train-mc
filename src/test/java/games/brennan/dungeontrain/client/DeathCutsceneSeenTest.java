package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first-ever death cinematic: unseen until it plays out, then seen for good. */
class DeathCutsceneSeenTest {

    @Test
    void firstUntilMarked(@TempDir Path dir) {
        Path file = dir.resolve("sub").resolve(DeathCutsceneSeen.FILE_NAME);
        DeathCutsceneSeen seen = new DeathCutsceneSeen(file);
        assertTrue(seen.isFirstEver());
        seen.markSeen();
        assertFalse(seen.isFirstEver());
        assertFalse(new DeathCutsceneSeen(file).isFirstEver());
    }

    @Test
    void damagedFileStillCountsAsSeen(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(DeathCutsceneSeen.FILE_NAME);
        Files.writeString(file, "not json {");
        assertFalse(new DeathCutsceneSeen(file).isFirstEver());
    }

    @Test
    void failedWriteStillRemembersForTheSession(@TempDir Path dir) throws Exception {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "a file where a directory should be");
        DeathCutsceneSeen seen = new DeathCutsceneSeen(blocker.resolve(DeathCutsceneSeen.FILE_NAME));
        seen.markSeen();
        assertFalse(seen.isFirstEver());
    }
}
