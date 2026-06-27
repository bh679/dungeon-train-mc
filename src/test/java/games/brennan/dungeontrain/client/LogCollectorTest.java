package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the bug-report log collector's helpers: tailing a (possibly live) log,
 * gzip round-tripping, and home-dir redaction. No Minecraft runtime needed.
 */
class LogCollectorTest {

    @Test
    void readTailReturnsWholeFileWhenSmallerThanCap(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("small.log");
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        Files.write(f, content);
        assertArrayEquals(content, LogCollector.readTail(f, 1024));
    }

    @Test
    void readTailReturnsOnlyTrailingBytesWhenLarger(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("big.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("line").append(i).append('\n');
        }
        byte[] all = sb.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(f, all);

        int cap = 100;
        byte[] tail = LogCollector.readTail(f, cap);
        assertEquals(cap, tail.length);
        byte[] expected = new byte[cap];
        System.arraycopy(all, all.length - cap, expected, 0, cap);
        assertArrayEquals(expected, tail, "tail must be the last cap bytes");
    }

    @Test
    void gzipRoundTrips() throws IOException {
        byte[] original = "the train disappeared at chunk 42\n".repeat(50).getBytes(StandardCharsets.UTF_8);
        byte[] gz = LogCollector.gzip(original);
        assertTrue(gz.length > 0);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            assertArrayEquals(original, in.readAllBytes());
        }
    }

    @Test
    void redactHomeReplacesHomePathWithTilde() {
        String home = System.getProperty("user.home", "");
        String line = "[INFO] at " + home + "/.minecraft/mods/foo loaded";
        String redacted = new String(LogCollector.redactHome(line.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        if (!home.isBlank()) {
            assertTrue(redacted.contains("~/.minecraft/mods/foo"), "home dir path replaced with ~");
            assertFalse(redacted.contains(home), "raw home path removed");
        }
    }
}
