package games.brennan.dungeontrain.client.live;

import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpegManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** DT's own ffmpeg install: only the two binaries are unpacked, and a failure is told apart by its cause. */
final class FfmpegInstallerTest {

    private static final Set<String> NAMES = Set.of("ffmpeg.exe", "ffprobe.exe");

    private static byte[] zip(String... namesAndBodies) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndBodies.length; i += 2) {
                out.putNextEntry(new ZipEntry(namesAndBodies[i]));
                out.write(namesAndBodies[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("a failure from Vista's run check means won't run; anything else, including wrapped, means download")
    void failureKinds() {
        RuntimeException unusable = new FFmpegManager.UnusableFFmpegException("no answer", null);
        assertEquals(FfmpegInstaller.Failure.WONT_RUN, FfmpegInstaller.failureOf(unusable));
        assertEquals(FfmpegInstaller.Failure.WONT_RUN,
            FfmpegInstaller.failureOf(new CompletionException(unusable)));
        assertEquals(FfmpegInstaller.Failure.DOWNLOAD,
            FfmpegInstaller.failureOf(new RuntimeException("FFmpeg setup failed. Aborting.", new IOException("reset"))));
    }

    @Test
    @DisplayName("only ffmpeg and ffprobe are unpacked, from any folder, into the bin folder")
    void extractsOnlyTheBinaries(@TempDir Path dir) throws Exception {
        byte[] archive = zip("build/bin/ffmpeg.exe", "A", "build/bin/ffprobe.exe", "B",
            "build/bin/ffplay.exe", "C", "LICENSE.txt", "L");
        FfmpegInstaller.extractBinaries(new ByteArrayInputStream(archive), dir, NAMES);
        assertEquals("A", Files.readString(dir.resolve("ffmpeg.exe")));
        assertEquals("B", Files.readString(dir.resolve("ffprobe.exe")));
        assertFalse(Files.exists(dir.resolve("ffplay.exe")));
        assertFalse(Files.exists(dir.resolve("LICENSE.txt")));
    }

    @Test
    @DisplayName("a path-escaping entry is written as its bare file name inside the bin folder, never outside")
    void zipSlipStaysInside(@TempDir Path dir) throws Exception {
        Path bin = Files.createDirectory(dir.resolve("bin"));
        byte[] archive = zip("../../ffmpeg.exe", "A", "..\\ffprobe.exe", "B");
        FfmpegInstaller.extractBinaries(new ByteArrayInputStream(archive), bin, NAMES);
        assertEquals("A", Files.readString(bin.resolve("ffmpeg.exe")));
        assertEquals("B", Files.readString(bin.resolve("ffprobe.exe")));
        assertFalse(Files.exists(dir.resolve("ffmpeg.exe")));
    }

    @Test
    @DisplayName("an archive missing either binary is refused")
    void missingBinaryFails(@TempDir Path dir) throws Exception {
        byte[] archive = zip("bin/ffmpeg.exe", "A");
        assertThrows(IOException.class,
            () -> FfmpegInstaller.extractBinaries(new ByteArrayInputStream(archive), dir, NAMES));
    }
}
