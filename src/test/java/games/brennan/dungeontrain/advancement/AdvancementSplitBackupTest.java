package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.advancement.AdvancementSplitBackup.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-time backup before advancements are split by difficulty. What must hold: earlier history is
 * copied before anything else touches it, the copy never overwrites, and it happens exactly once.
 */
class AdvancementSplitBackupTest {

    private static final String UUID_A = "380df991-f603-344c-a090-369bad2a924a";
    private static final String UUID_B = "12345678-1234-1234-1234-123456789abc";
    private static final String GRANTED = "{\"granted\":[\"dungeontrain:dungeon_train/carts_100\"]}";

    private final AtomicInteger restorePoints = new AtomicInteger();

    private Outcome run(Path dir, Path legacy) {
        return AdvancementSplitBackup.run(dir, legacy, () -> "archive-" + restorePoints.incrementAndGet());
    }

    @Test
    @DisplayName("earlier advancements are copied, a restore point is taken, and the install is marked")
    void backsUpPreSplitProfiles(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("achievements"));
        Files.writeString(dir.resolve(UUID_A + ".json"), GRANTED);

        assertEquals(Outcome.BACKED_UP, run(dir, root.resolve("legacy")));
        assertEquals(1, restorePoints.get());
        assertEquals(GRANTED, Files.readString(dir.resolve("pre-split").resolve(UUID_A + ".json")));
        assertTrue(Files.exists(dir.resolve(AdvancementSplitBackup.MARKER)));
    }

    @Test
    @DisplayName("it runs once — a marked install takes no second restore point")
    void runsOnce(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("achievements"));
        Files.writeString(dir.resolve(UUID_A + ".json"), GRANTED);
        run(dir, root.resolve("legacy"));

        Files.writeString(dir.resolve(UUID_A + ".json"), "{\"granted\":[]}");
        assertEquals(Outcome.ALREADY_DONE, run(dir, root.resolve("legacy")));
        assertEquals(1, restorePoints.get());
        assertEquals(GRANTED, Files.readString(dir.resolve("pre-split").resolve(UUID_A + ".json")),
            "the kept copy is the pre-split history, never a later state");
    }

    @Test
    @DisplayName("a fresh install is marked with no restore point and no copy")
    void freshInstall(@TempDir Path root) {
        Path dir = root.resolve("achievements");
        assertEquals(Outcome.NOTHING_TO_BACK_UP, run(dir, root.resolve("legacy")));
        assertEquals(0, restorePoints.get());
        assertTrue(Files.exists(dir.resolve(AdvancementSplitBackup.MARKER)));
        assertFalse(Files.exists(dir.resolve("pre-split")));
    }

    @Test
    @DisplayName("only <uuid>.json counts — per-difficulty and shared files are already split")
    void onlyPreSplitNamesCount(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("achievements"));
        Files.writeString(dir.resolve(UUID_A + ".hard.json"), GRANTED);
        Files.writeString(dir.resolve(UUID_A + ".shared.json"), GRANTED);
        Files.writeString(dir.resolve("notes.json"), "{}");

        assertTrue(AdvancementSplitBackup.preSplitFiles(dir).isEmpty());
        assertEquals(Outcome.NOTHING_TO_BACK_UP, run(dir, root.resolve("legacy")));
    }

    @Test
    @DisplayName("a profile still in the old config folder is backed up too")
    void legacyFolderIsBackedUp(@TempDir Path root) throws IOException {
        Path dir = root.resolve("achievements");
        Path legacy = Files.createDirectories(root.resolve("legacy"));
        Files.writeString(legacy.resolve(UUID_B + ".json"), GRANTED);

        assertEquals(Outcome.BACKED_UP, run(dir, legacy));
        assertEquals(GRANTED, Files.readString(dir.resolve("pre-split").resolve(UUID_B + ".json")));
    }

    @Test
    @DisplayName("a failed restore point still keeps the copy")
    void restorePointFailureStillCopies(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("achievements"));
        Files.writeString(dir.resolve(UUID_A + ".json"), GRANTED);

        Outcome outcome = AdvancementSplitBackup.run(dir, root.resolve("legacy"), () -> {
            throw new IOException("disk full");
        });
        assertEquals(Outcome.BACKED_UP, outcome);
        assertTrue(Files.exists(dir.resolve("pre-split").resolve(UUID_A + ".json")));
    }
}
