package games.brennan.dungeontrain.net.relay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveUrlsTest {

    @Test
    void httpsAnywhere() {
        assertTrue(LiveUrls.allowed("https://pub-x.r2.dev/main/s1/live.m3u8", ".m3u8"));
        assertTrue(LiveUrls.allowed("HTTPS://x/seg00001.ts", ".ts"));
        assertTrue(LiveUrls.allowed("https://acct.r2.cloudflarestorage.com/dt-live/a?X-Amz-Signature=1", ""));
    }

    @Test
    void plainHttpOnlyOnLoopback() {
        assertTrue(LiveUrls.allowed("http://127.0.0.1:3412/test/live.m3u8", ".m3u8"));
        assertTrue(LiveUrls.allowed("http://localhost:3412/live.m3u8", ".m3u8"));
        assertTrue(LiveUrls.allowed("http://[::1]:3412/live.m3u8", ".m3u8"));
        assertFalse(LiveUrls.allowed("http://evil.example/live.m3u8", ".m3u8"));
        assertFalse(LiveUrls.allowed("http://127.0.0.1.evil.example/live.m3u8", ".m3u8"));
        assertFalse(LiveUrls.allowed("http://10.0.0.5/live.m3u8", ".m3u8"), "LAN is not loopback");
    }

    @Test
    void otherSchemesSuffixesAndJunkAreRefused() {
        assertFalse(LiveUrls.allowed("file:///tmp/live.m3u8", ".m3u8"));
        assertFalse(LiveUrls.allowed("ftp://x/live.m3u8", ".m3u8"));
        assertFalse(LiveUrls.allowed("https://x/live.m3u8.php", ".m3u8"));
        assertFalse(LiveUrls.allowed("https://x/seg.ts", ".m3u8"));
        assertFalse(LiveUrls.allowed("https:///live.m3u8", ".m3u8"), "no host");
        assertFalse(LiveUrls.allowed("https://user@x/live.m3u8", ".m3u8"), "credentials in the URL");
        assertFalse(LiveUrls.allowed("https://x/a b.m3u8", ".m3u8"), "not a URI");
        assertFalse(LiveUrls.allowed("", ""));
        assertFalse(LiveUrls.allowed(null, ""));
        assertNull(LiveUrls.orNull("http://evil.example/seg.ts", ".ts"));
        assertEquals("https://x/seg.ts", LiveUrls.orNull("https://x/seg.ts", ".ts"));
    }
}
