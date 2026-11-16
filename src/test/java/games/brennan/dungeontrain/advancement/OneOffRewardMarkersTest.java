package games.brennan.dungeontrain.advancement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneOffRewardMarkersTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void notGivenUntilMarked(@TempDir Path dir) {
        OneOffRewardMarkers markers = new OneOffRewardMarkers(dir.resolve("rewards"));
        assertFalse(markers.isGiven(PLAYER, "darkroom_disc"));
        assertTrue(markers.markGiven(PLAYER, "darkroom_disc"));
        assertTrue(markers.isGiven(PLAYER, "darkroom_disc"));
    }

    @Test
    void markIsPerPlayerAndPerKey(@TempDir Path dir) {
        OneOffRewardMarkers markers = new OneOffRewardMarkers(dir);
        markers.markGiven(PLAYER, "darkroom_disc");
        assertFalse(markers.isGiven(OTHER, "darkroom_disc"));
        assertFalse(markers.isGiven(PLAYER, "something_else"));
        markers.markGiven(PLAYER, "something_else");
        assertTrue(markers.isGiven(PLAYER, "darkroom_disc"));
        assertTrue(markers.isGiven(PLAYER, "something_else"));
    }

    @Test
    void unreadableFileCountsAsGiven(@TempDir Path dir) throws Exception {
        OneOffRewardMarkers markers = new OneOffRewardMarkers(dir);
        Files.createDirectories(markers.path(PLAYER)); // a directory where the file should be
        assertTrue(markers.isGiven(PLAYER, "darkroom_disc"));
    }
}
