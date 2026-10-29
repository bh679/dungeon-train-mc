package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** DT's own ffmpeg download: only the pinned bytes are accepted, and only the two binaries are unpacked. */
final class FfmpegInstallerTest {

    private static final Set<String> NAMES = Set.of("ffmpeg.exe", "ffprobe.exe");

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

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
    @DisplayName("the pinned bytes are copied through, with progress up to 100")
    void copiesPinnedBytes() throws Exception {
        byte[] data = new byte[200_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> seen = new ArrayList<>();
        FfmpegInstaller.copyVerified(new ByteArrayInputStream(data), out, data.length, sha256(data), seen::add);
        assertArrayEquals(data, out.toByteArray());
        assertEquals(100, seen.get(seen.size() - 1));
    }

    @Test
    @DisplayName("a wrong hash, a short download or an oversized one is refused")
    void refusesAnythingElse() throws Exception {
        byte[] data = "ffmpeg".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(data);
        assertThrows(IOException.class, () -> FfmpegInstaller.copyVerified(
            new ByteArrayInputStream(data), new ByteArrayOutputStream(), data.length, sha256(new byte[] {1}), p -> {}));
        assertThrows(IOException.class, () -> FfmpegInstaller.copyVerified(
            new ByteArrayInputStream(data), new ByteArrayOutputStream(), data.length + 1, hash, p -> {}));
        assertThrows(IOException.class, () -> FfmpegInstaller.copyVerified(
            new ByteArrayInputStream(data), new ByteArrayOutputStream(), data.length - 1, hash, p -> {}));
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
