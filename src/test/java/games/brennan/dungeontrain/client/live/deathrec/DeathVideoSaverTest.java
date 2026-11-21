package games.brennan.dungeontrain.client.live.deathrec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeathVideoSaverTest {

    @Test
    void namesSortByTime() {
        assertEquals("death-2026-11-21_23-58-04", DeathVideoSaver.baseName(LocalDateTime.of(2026, 11, 21, 23, 58, 4)));
    }

    @Test
    void twoDeathsInOneSecondGetTwoFiles(@TempDir Path dir) throws IOException {
        String base = "death-2026-11-21_23-58-04";
        assertEquals(dir.resolve(base + ".mp4"), DeathVideoSaver.freePath(dir, base, "mp4"));
        Files.createFile(dir.resolve(base + ".mp4"));
        assertEquals(dir.resolve(base + "-2.mp4"), DeathVideoSaver.freePath(dir, base, "mp4"));
    }

    @Test
    void withoutRemuxTheRawStreamIsKept(@TempDir Path dir) throws IOException {
        byte[] ts = new byte[DeathRecRing.PACKET * 3];
        Path kept = DeathVideoSaver.save(dir.resolve("death-videos"), ts, LocalDateTime.of(2026, 1, 2, 3, 4, 5), null, true);
        assertEquals("death-2026-01-02_03-04-05.ts", kept.getFileName().toString());
        assertEquals(ts.length, Files.size(kept));
    }
}
