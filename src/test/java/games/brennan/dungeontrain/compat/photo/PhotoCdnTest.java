package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoCdnTest {
    private HttpServer server;
    private String base;
    private final AtomicInteger cdnStatus = new AtomicInteger(200);
    private final java.util.concurrent.atomic.AtomicReference<String> cdnType = new java.util.concurrent.atomic.AtomicReference<>("image/jpeg");
    private final AtomicInteger cdnHits = new AtomicInteger();
    private final AtomicInteger relayHits = new AtomicInteger();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        PhotoCdn.resetBreaker();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cdn", ex -> {
            cdnHits.incrementAndGet();
            byte[] body = "cdn".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", cdnType.get());
            ex.sendResponseHeaders(cdnStatus.get(), body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/relay", ex -> {
            relayHits.incrementAndGet();
            byte[] body = "relay".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        PhotoCdn.resetBreaker();
    }

    private String fetch(String cdnUrl) throws Exception {
        HttpResponse<byte[]> resp = PhotoCdn.send(http, cdnUrl, base + "/relay", Duration.ofSeconds(5), url -> url != null && !url.isBlank());
        return new String(resp.body(), StandardCharsets.UTF_8);
    }

    @Test
    void onlyHttpsOnTheCdnHostIsAccepted() {
        assertTrue(PhotoCdn.acceptable("https://photos.dungeon.training/p/live/abc.jpg"));
        assertFalse(PhotoCdn.acceptable("http://photos.dungeon.training/p/live/abc.jpg"));
        assertFalse(PhotoCdn.acceptable("https://evil.example/p/live/abc.jpg"));
        assertFalse(PhotoCdn.acceptable("https://photos.dungeon.training.evil.example/x.jpg"));
        assertFalse(PhotoCdn.acceptable("https://user@photos.dungeon.training/x.jpg"));
        assertFalse(PhotoCdn.acceptable("https://photos.dungeon.training:8443/x.jpg"));
        assertFalse(PhotoCdn.acceptable(""));
        assertFalse(PhotoCdn.acceptable(null));
        assertFalse(PhotoCdn.acceptable("not a url"));
    }

    @Test
    void urlOfDropsMissingOrForeignUrls() {
        JsonObject row = new JsonObject();
        assertEquals("", PhotoCdn.urlOf(row, "image"));
        row.addProperty("image", "https://evil.example/x.jpg");
        assertEquals("", PhotoCdn.urlOf(row, "image"));
        row.addProperty("image", "https://photos.dungeon.training/p/live/a.jpg");
        assertEquals("https://photos.dungeon.training/p/live/a.jpg", PhotoCdn.urlOf(row, "image"));
    }

    @Test
    void theCdnCopyIsUsedWhenItAnswers() throws Exception {
        assertEquals("cdn", fetch(base + "/cdn"));
        assertEquals(0, relayHits.get());
    }

    @Test
    void noCdnUrlMeansTheRelay() throws Exception {
        assertEquals("relay", fetch(""));
        assertEquals(0, cdnHits.get());
    }

    @Test
    void aRemovedPictureFallsBackWithoutTrippingTheBreaker() throws Exception {
        cdnStatus.set(404);
        for (int i = 0; i < PhotoCdn.BREAKER_FAILURES + 1; i++) assertEquals("relay", fetch(base + "/cdn"));
        assertEquals(PhotoCdn.BREAKER_FAILURES + 1, cdnHits.get(), "404s keep trying the CDN");
    }

    @Test
    void aBrokenCdnIsSkippedAfterRepeatedFailures() throws Exception {
        cdnStatus.set(502);
        for (int i = 0; i < PhotoCdn.BREAKER_FAILURES; i++) assertEquals("relay", fetch(base + "/cdn"));
        assertEquals(PhotoCdn.BREAKER_FAILURES, cdnHits.get());
        assertEquals("relay", fetch(base + "/cdn"));
        assertEquals(PhotoCdn.BREAKER_FAILURES, cdnHits.get(), "breaker open: straight to the relay");
    }

    @Test
    void anUnreachableCdnFallsBack() throws Exception {
        assertEquals("relay", fetch("http://127.0.0.1:1/cdn"));
        assertEquals(1, relayHits.get());
    }

    @Test
    void aBotCheckOrChallengePageCountsAgainstTheCdn() throws Exception {
        cdnStatus.set(403);
        for (int i = 0; i < PhotoCdn.BREAKER_FAILURES; i++) assertEquals("relay", fetch(base + "/cdn"));
        assertEquals("relay", fetch(base + "/cdn"));
        assertEquals(PhotoCdn.BREAKER_FAILURES, cdnHits.get(), "403s trip the breaker");
    }

    @Test
    void aNonImage200IsNotThePicture() throws Exception {
        cdnType.set("text/html");
        assertEquals("relay", fetch(base + "/cdn"));
        assertEquals(1, relayHits.get());
    }
}
