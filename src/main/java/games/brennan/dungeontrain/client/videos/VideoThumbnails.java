package games.brennan.dungeontrain.client.videos;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * YouTube thumbnails for the Videos page, fetched on demand and held for the session.
 *
 * <p>YouTube is the one platform with a keyless, stable thumbnail URL
 * ({@code img.youtube.com/vi/<id>/mqdefault.jpg}, 320×180); the others draw a platform-coloured
 * tile instead (see {@link VideoList}). The list asks for a row's thumbnail every frame it is
 * visible, so — the {@code BuilderPhotoTextures} rule — <b>misses are cached as hard as hits</b>: a
 * blocked host (mainland China) costs one failed request per video, not one per frame.</p>
 *
 * <p>Downloads run on the HTTP client's threads; decoding into a {@link DynamicTexture} happens on
 * the render thread via {@link Minecraft#execute}. At most {@link #MAX_IN_FLIGHT} downloads run at
 * once — the rest queue in the order they were asked for, which is scroll order.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VideoThumbnails {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A decoded thumbnail and its real pixel size, so the list can letterbox rather than squash. */
    public record Thumb(ResourceLocation texture, int width, int height) {}

    private static final int MAX_IN_FLIGHT = 4;
    /** mqdefault is ~10–20 KB; anything past this is not a thumbnail. */
    private static final int MAX_BYTES = 512 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** Absent value = looked and found nothing. Absent key = not looked yet. Render thread only. */
    private static final Map<String, Optional<Thumb>> CACHE = new HashMap<>();
    /** Keys that have been asked for but not yet resolved (queued or in flight). Render thread only. */
    private static final ArrayDeque<String> QUEUE = new ArrayDeque<>();
    private static int inFlight;

    private VideoThumbnails() {}

    /**
     * The thumbnail for a row if it is already decoded; otherwise {@code null} — and, for a YouTube
     * row not yet asked about, the download is started. Render thread only.
     */
    public static Thumb textureFor(VideoEntry v) {
        if (v == null || v.platform() != VideoEntry.Platform.YOUTUBE || v.videoId() == null) {
            return null;
        }
        String key = v.videoId();
        Optional<Thumb> hit = CACHE.get(key);
        if (hit != null) {
            return hit.orElse(null);
        }
        if (!QUEUE.contains(key)) {
            QUEUE.addLast(key);
            pump();
        }
        return null;
    }

    /** Start downloads until the in-flight cap is reached. Render thread only. */
    private static void pump() {
        while (inFlight < MAX_IN_FLIGHT && !QUEUE.isEmpty()) {
            String key = QUEUE.pollFirst();
            if (CACHE.containsKey(key)) continue;
            inFlight++;
            download(key);
        }
    }

    private static void download(String videoId) {
        String url = "https://img.youtube.com/vi/" + videoId + "/mqdefault.jpg";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "image/jpeg,image/*")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                    .whenComplete((resp, err) -> {
                        byte[] bytes = null;
                        if (err == null && resp.statusCode() / 100 == 2
                                && resp.body() != null && resp.body().length > 0 && resp.body().length <= MAX_BYTES) {
                            bytes = resp.body();
                        } else if (err != null) {
                            LOGGER.debug("[DungeonTrain] thumbnail {} failed: {}", videoId, err.toString());
                        }
                        byte[] done = bytes;
                        Minecraft.getInstance().execute(() -> settle(videoId, done));
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] thumbnail {} request failed to start: {}", videoId, t.toString());
            Minecraft.getInstance().execute(() -> settle(videoId, null));
        }
    }

    /** Decode (or record the miss), then start the next queued download. Render thread. */
    private static void settle(String videoId, byte[] bytes) {
        inFlight = Math.max(0, inFlight - 1);
        CACHE.put(videoId, bytes == null ? Optional.empty() : decode(videoId, bytes));
        pump();
    }

    private static Optional<Thumb> decode(String videoId, byte[] bytes) {
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
            int w = image.getWidth();
            int h = image.getHeight();
            // DynamicTexture takes ownership of the image; read the size first.
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation location = Minecraft.getInstance().getTextureManager()
                    .register("dungeontrain_video_thumb", texture);
            return Optional.of(new Thumb(location, w, h));
        } catch (Exception e) {
            // A truncated or non-image body shouldn't take the page down; the row gets the tile.
            LOGGER.debug("[DungeonTrain] thumbnail {} undecodable: {}", videoId, e.toString());
            return Optional.empty();
        }
    }
}
