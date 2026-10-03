package games.brennan.dungeontrain.client.deathphotos;

import games.brennan.dungeontrain.client.snapshot.RideSnapshotExporter;
import net.minecraft.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where a saved disposable-camera photo goes: {@code screenshots/dungeontrain-photo-<datetime>[_n].png}. */
final class CameraPhotoExporter {

    private CameraPhotoExporter() {}

    /** A not-yet-existing PNG path in the screenshots folder (created if needed). */
    static Path uniquePath() throws IOException {
        Path dir = RideSnapshotExporter.screenshotsDir();
        Files.createDirectories(dir);
        String base = "dungeontrain-photo-" + Util.getFilenameFormattedDateTime();
        Path candidate = dir.resolve(base + ".png");
        for (int n = 1; Files.exists(candidate); n++) {
            candidate = dir.resolve(base + "_" + n + ".png");
        }
        return candidate;
    }
}
