package games.brennan.dungeontrain.client.logo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Where the menu's slideshow gets its photos: the community's tributed photos (the website's Live
 * Gallery, the relay's cap-less {@code /tributes/photos} list and images, newest tribute first),
 * shuffled; or, while that list is unreachable or empty, the ride photos bundled in the jar.
 *
 * <p>Anonymous GETs with no identity, so no consent gate (as the title screen's donation summary).
 * The relay answers an image only while its photo is live and tributed, so a photo taken down
 * stops showing. Everything here runs off the render thread; {@link MenuBackdrop} uploads.</p>
 */
final class MenuPhotoSource {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BUNDLED = 7;
    private static final int LIST_LIMIT = 60;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern PHOTO_ID = Pattern.compile("^[1-9][0-9]{0,15}$");
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();

    /** A decoded photo, and the photographer to credit (null for the bundled ones). */
    record Loaded(NativeImage image, @Nullable String author) {}

    private record Tribute(String id, String author) {}

    private CompletableFuture<List<Tribute>> list;
    private final AtomicInteger next = new AtomicInteger();

    /** The next photo, decoded; falls back to the bundled photos whenever a tributed one can't be had. */
    CompletableFuture<Loaded> next() {
        if (list == null) list = fetchList();
        return list.thenCompose(tributes -> {
            if (tributes.isEmpty()) return CompletableFuture.supplyAsync(this::bundled);
            Tribute t = tributes.get(Math.floorMod(next.getAndIncrement(), tributes.size()));
            return download(t).exceptionally(e -> {
                LOGGER.debug("[DungeonTrain] menu tribute photo {} unavailable: {}", t.id(), e.toString());
                return bundled();
            });
        });
    }

    /** The cap-less tributes API beside the live relay's capability path. */
    private static String tributesBase() {
        String base = DungeonTrain.liveRelayBaseUrl();
        return base.substring(0, base.lastIndexOf('/')) + "/tributes";
    }

    private static CompletableFuture<List<Tribute>> fetchList() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(tributesBase() + "/photos?limit=" + LIST_LIMIT))
                .timeout(TIMEOUT).header("Accept", "application/json").GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).handle((resp, error) -> {
            List<Tribute> out = new ArrayList<>();
            try {
                if (error != null || resp.statusCode() != 200) return out;
                JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonArray photos = body.getAsJsonArray("photos");
                if (photos == null) return out;
                for (JsonElement el : photos) {
                    JsonObject p = el.getAsJsonObject();
                    String id = p.has("id") ? p.get("id").getAsString() : "";
                    if (!PHOTO_ID.matcher(id).matches()) continue;
                    String author = p.has("author") && !p.get("author").isJsonNull() ? p.get("author").getAsString() : "";
                    out.add(new Tribute(id, author));
                }
                Collections.shuffle(out);
            } catch (RuntimeException e) {
                LOGGER.debug("[DungeonTrain] menu tributes list unreadable", e);
                out.clear();
            }
            if (out.isEmpty()) LOGGER.info("[DungeonTrain] No tributed photos reachable for the menu; showing the bundled ride photos");
            return out;
        });
    }

    private static CompletableFuture<Loaded> download(Tribute t) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(tributesBase() + "/photos/" + t.id() + "/image"))
                .timeout(TIMEOUT).GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).thenApply(resp -> {
            if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
            byte[] bytes = resp.body();
            if (bytes.length > MAX_IMAGE_BYTES) throw new IllegalStateException("too large: " + bytes.length);
            try {
                return new Loaded(decode(new ByteArrayInputStream(bytes)), t.author().isBlank() ? null : t.author());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** One of the jar's ride photos, in turn. */
    private Loaded bundled() {
        int i = 1 + Math.floorMod(next.getAndIncrement(), BUNDLED);
        ResourceLocation file = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID,
                String.format("textures/gui/menu_backdrop/ride-%02d.jpg", i));
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(file)) {
            return new Loaded(decode(in), null);
        } catch (Exception e) {
            throw new IllegalStateException("bundled menu photo " + file + " unreadable", e);
        }
    }

    /** JPEG or PNG via ImageIO (Minecraft's own reader takes PNG only), as ABGR for a NativeImage. */
    private static NativeImage decode(InputStream in) throws Exception {
        BufferedImage image = ImageIO.read(in);
        if (image == null) throw new IllegalStateException("not an image ImageIO can read");
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        NativeImage out = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = argb[y * w + x];
                out.setPixelRGBA(x, y, 0xFF000000 | (c & 0xFF) << 16 | (c & 0xFF00) | (c >> 16 & 0xFF));
            }
        }
        return out;
    }
}
