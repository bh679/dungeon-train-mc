package games.brennan.dungeontrain.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crash detector's contract: what is written can be read back, a clear leaves nothing, and a
 * missing or damaged file reads as "no run" rather than throwing — because a broken record must
 * never stop the game reaching the title screen.
 */
class CrashRunStateTest {

    private static final CrashRunState.RunState RUN =
            new CrashRunState.RunState("Dungeon Train 1757400000000", "Dungeon Train", 1757400000000L,
                    CrashRunState.Status.ACTIVE);

    @Test
    void writeThenReadRoundTrips(@TempDir Path root) {
        assertTrue(CrashRunState.write(root, RUN));
        assertTrue(Files.isRegularFile(CrashRunState.file(root)));

        Optional<CrashRunState.RunState> read = CrashRunState.read(root);
        assertTrue(read.isPresent());
        assertEquals(RUN, read.get());
    }

    @Test
    void absentFileReadsAsNoRun(@TempDir Path root) {
        assertTrue(CrashRunState.read(root).isEmpty());
        assertFalse(CrashRunState.clear(root), "nothing to clear");
    }

    @Test
    void clearRemovesTheRecord(@TempDir Path root) {
        CrashRunState.write(root, RUN);
        assertTrue(CrashRunState.clear(root));
        assertTrue(CrashRunState.read(root).isEmpty());
        assertFalse(Files.exists(CrashRunState.file(root)));
    }

    @Test
    void malformedFileReadsAsNoRun(@TempDir Path root) throws Exception {
        Path file = CrashRunState.file(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json");
        assertTrue(CrashRunState.read(root).isEmpty());

        Files.writeString(file, "{\"status\":\"ACTIVE\"}"); // no levelId → nothing to reopen
        assertTrue(CrashRunState.read(root).isEmpty());
    }

    @Test
    void salvagingFlipsStatusAndSurvivesRewrite(@TempDir Path root) {
        CrashRunState.write(root, RUN);
        CrashRunState.RunState salvaging = RUN.salvaging();
        assertEquals(CrashRunState.Status.SALVAGING, salvaging.status());
        assertEquals(RUN.levelId(), salvaging.levelId());

        CrashRunState.write(root, salvaging);
        assertEquals(CrashRunState.Status.SALVAGING, CrashRunState.read(root).orElseThrow().status());
    }

    @Test
    void unknownStatusFallsBackToActive(@TempDir Path root) throws Exception {
        Path file = CrashRunState.file(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"levelId\":\"World 1\",\"status\":\"something-new\"}");
        CrashRunState.RunState read = CrashRunState.read(root).orElseThrow();
        assertEquals(CrashRunState.Status.ACTIVE, read.status());
        assertEquals("World 1", read.worldName(), "worldName defaults to the level id");
    }

    @Test
    void runStateRequiresALevelId() {
        assertThrows(IllegalArgumentException.class,
                () -> new CrashRunState.RunState("", "x", 0L, CrashRunState.Status.ACTIVE));
    }
}
