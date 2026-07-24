package games.brennan.dungeontrain.net.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.net.RideGalleryPacket;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Off-thread uploader for the death-screen ride-photo gallery → the relay's
 * {@code POST /<CAP>/shots/ingest} endpoint (one {@code multipart/form-data} POST per photo, carrying
 * the JPEG plus the player uuid + the photo's facets). Mirrors {@link BookStatsClient}'s fire-and-forget
 * pattern (own {@link HttpClient}, HTTP/1.1-pinned, no-throw, best-effort) — a dropped photo just never
 * appears on the Photos page; it never affects the tick or the Discord death report.
 *
 * <p>The relay base URL already embeds the capability token in its path (see
 * {@link DungeonTrain#relayBaseUrl()}), so the ingest cap ships in-jar exactly like the other DT relay
 * calls (books/telemetry) and is independently revocable.</p>
 */
public final class ShotUploadClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    // See BookStatsClient: HTTP/1.1 so plaintext http:// (local 127.0.0.1 testing) works; harmless in prod.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private ShotUploadClient() {}

    /** Upload every photo in the gallery for {@code playerId}, off-thread and best-effort. */
    public static void uploadGallery(UUID playerId, List<RideGalleryPacket.Photo> photos) {
        if (playerId == null || photos == null || photos.isEmpty()) return;
        String uuid = playerId.toString().replace("-", ""); // dashless-lowercase, matching the relay key
        long now = System.currentTimeMillis();
        for (int i = 0; i < photos.size(); i++) {
            // Distinct ts per photo so same-ms uploads don't collide on the relay's <ts>.png filename.
            uploadOne(uuid, now + i, photos.get(i));
        }
    }

    private static void uploadOne(String uuid, long ts, RideGalleryPacket.Photo photo) {
        try {
            if (photo.jpeg() == null || photo.jpeg().length == 0) return;
            String boundary = "----dtshot" + Long.toHexString(ts) + Integer.toHexString(System.identityHashCode(photo));
            byte[] body = multipart(boundary, uuid, ts, photo);
            HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + "/shots/ingest"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, err) -> {
                        if (err != null) {
                            LOGGER.debug("[DungeonTrain] shot upload failed: {}", err.toString());
                        } else if (resp.statusCode() / 100 != 2) {
                            LOGGER.debug("[DungeonTrain] shot upload -> HTTP {}", resp.statusCode());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] shot upload failed to start: {}", t.toString());
        }
    }

    /** Build a multipart/form-data body: the facet fields as text parts + the JPEG as a file part. */
    private static byte[] multipart(String boundary, String uuid, long ts, RideGalleryPacket.Photo p) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, boundary, "uuid", uuid);
        field(out, boundary, "ts", Long.toString(ts));
        field(out, boundary, "version", VersionInfo.VERSION); // mod version → relay 'version' facet
        field(out, boundary, "tag", p.tag());
        field(out, boundary, "biome", p.biome());
        field(out, boundary, "band", p.band());
        field(out, boundary, "difficulty", Integer.toString(p.difficulty()));
        field(out, boundary, "cart", Integer.toString(p.cart()));
        field(out, boundary, "gfx", p.gfx()); // graphics-stack tag (dh/shaders/mode) → relay 'gfx' facet
        field(out, boundary, "shaderpack", p.shaderpack()); // active Iris/Oculus pack name → relay 'shaderpack' facet
        field(out, boundary, "photoid", p.photoId()); // client-generated id → keys the later 'user-saved' mark
        // File part.
        ascii(out, "--" + boundary + "\r\n");
        ascii(out, "Content-Disposition: form-data; name=\"image\"; filename=\"ride.jpg\"\r\n");
        ascii(out, "Content-Type: image/jpeg\r\n\r\n");
        out.writeBytes(p.jpeg());
        ascii(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        if (value == null) value = "";
        ascii(out, "--" + boundary + "\r\n");
        ascii(out, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        ascii(out, "\r\n");
    }

    private static void ascii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
