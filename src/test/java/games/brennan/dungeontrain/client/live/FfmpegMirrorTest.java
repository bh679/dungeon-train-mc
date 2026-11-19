package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FfmpegMirrorTest {

    @Test
    @DisplayName("Windows x64 gets DT's mirror; other platforms fall back to Vista's own sources")
    void platforms() {
        assertEquals(FfmpegMirror.WINDOWS_X64, FfmpegMirror.forPlatform("Windows 11", "amd64"));
        assertEquals(FfmpegMirror.WINDOWS_X64, FfmpegMirror.forPlatform("Windows 10", "x86_64"));
        assertNull(FfmpegMirror.forPlatform("Windows 11", "aarch64"));
        assertNull(FfmpegMirror.forPlatform("Mac OS X", "aarch64"));
        assertNull(FfmpegMirror.forPlatform("Linux", "amd64"));
    }

    @Test
    @DisplayName("the mirror is https on brennan.games and pinned by a full SHA-256")
    void pinned() {
        FfmpegMirror m = FfmpegMirror.WINDOWS_X64;
        assertTrue(m.url().startsWith("https://brennan.games/"));
        assertTrue(m.sha256().matches("[0-9a-f]{64}"));
        assertTrue(m.size() > 0);
    }

    @Test
    @DisplayName("the dev override parses url,sha256,size and ignores anything malformed")
    void devOverride() {
        String sha = "a".repeat(64);
        assertEquals(new FfmpegMirror("http://127.0.0.1:8799/f.zip", sha, 12L),
            FfmpegMirror.parse("http://127.0.0.1:8799/f.zip," + sha + ",12"));
        assertNull(FfmpegMirror.parse(null));
        assertNull(FfmpegMirror.parse("http://x/f.zip,nothex,12"));
        assertNull(FfmpegMirror.parse("http://x/f.zip," + sha + ",big"));
        assertNull(FfmpegMirror.parse("http://x/f.zip"));
    }
}
