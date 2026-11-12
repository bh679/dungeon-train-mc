package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vista's Mirror is out of the game ({@link DisabledModContent#HIDDEN_ITEMS}): no bundled template places one and
 * no DT loot pool rolls one. Loot-table rolls are stripped at runtime, but DT's own pools and the blocks of its
 * templates are not, so a re-saved template or pool that brings a mirror back fails here.
 */
final class NoBundledMirrorsTest {

    private static final String MIRROR = "vista:mirror";

    @Test
    @DisplayName("no bundled template, sidecar or loot pool under data/dungeontrain mentions vista:mirror")
    void noBundledMirrors() throws IOException {
        Path data = RepoPaths.resources().resolve("data/dungeontrain");
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(data)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json") && !name.endsWith(".nbt")) continue;
                scanned++;
                if (read(file).contains(MIRROR)) offenders.add(data.relativize(file).toString());
            }
        }
        assertTrue(scanned > 100, "expected the bundled data tree, scanned " + scanned + " files");
        assertEquals(List.of(), offenders);
    }

    /** A {@code .nbt} is gzipped; its strings are modified UTF-8, which is plain ASCII for a block or item id. */
    private static String read(Path file) throws IOException {
        if (file.toString().endsWith(".json")) return Files.readString(file, StandardCharsets.UTF_8);
        try (InputStream raw = Files.newInputStream(file); InputStream in = new GZIPInputStream(raw)) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
