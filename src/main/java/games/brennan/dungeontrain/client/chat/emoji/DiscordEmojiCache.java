package games.brennan.dungeontrain.client.chat.emoji;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Discord's custom server emoji ({@code <:name:id>}) as images: fetched once from Discord's CDN, then
 * drawn through the {@code dungeontrain:discord_emoji} font ({@link DiscordEmojiGlyphProvider}).
 *
 * <p>Each loaded emoji gets its own character in plane-16 private use (U+100000 up). A slot is never
 * reused or handed out before its image is ready: a FontSet remembers a missing character until the
 * next resource reload, so a character asked for too early would stay blank. {@link EmojiText} therefore
 * writes {@code :name:} until {@link #slot} answers, and listeners re-draw the waiting chat lines once it
 * does. Images live for the session (at most {@link #MAX_SLOTS}, 4 KB each) and survive resource reloads,
 * since the provider reads them from here.</p>
 *
 * <p>Only {@code cdn.discordapp.com/emojis/<digits>.png} is ever fetched, at most {@link #MAX_BYTES}, and
 * only with the player's network consent. Animated emoji draw their first frame.</p>
 */
public final class DiscordEmojiCache {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final int FIRST_SLOT = 0x100000;
    static final int MAX_SLOTS = 4096;
    static final int SIZE = 32;
    private static final int MAX_BYTES = 256 * 1024;
    private static final Pattern ID = Pattern.compile("\\d{5,25}");
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    private static final Map<String, Integer> SLOT_BY_ID = new ConcurrentHashMap<>();
    private static final Map<Integer, NativeImage> IMAGE_BY_SLOT = new ConcurrentHashMap<>();
    private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();
    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();
    private static int nextSlot = FIRST_SLOT;

    private DiscordEmojiCache() {}

    /** The font character drawing emoji {@code id}, once its image has loaded. */
    public static OptionalInt slot(String id) {
        Integer s = SLOT_BY_ID.get(id);
        return s == null ? OptionalInt.empty() : OptionalInt.of(s);
    }

    /** Called with the emoji id on the client thread whenever one finishes loading. */
    public static void addListener(Consumer<String> listener) {
        LISTENERS.add(listener);
    }

    /** The image for a font character, or null when that slot is not loaded. Read by the glyph provider. */
    @Nullable
    static NativeImage image(int codepoint) {
        return IMAGE_BY_SLOT.get(codepoint);
    }

    /** Start fetching emoji {@code id} if it isn't loaded or already on its way. Any thread. */
    public static void request(String id) {
        if (id == null || !ID.matcher(id).matches() || SLOT_BY_ID.containsKey(id)) return;
        if (!DiscordPresenceClientConfig.isGranted()) return;
        if (REQUESTED.size() >= MAX_SLOTS || !REQUESTED.add(id)) return; // one attempt per id per session
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://cdn.discordapp.com/emojis/" + id + ".png?size=" + SIZE))
            .timeout(Duration.ofSeconds(10)).GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).thenAccept(resp -> {
            if (resp.statusCode() != 200 || resp.body().length == 0 || resp.body().length > MAX_BYTES) {
                LOGGER.debug("[DungeonTrain] custom emoji {} not loaded (HTTP {})", id, resp.statusCode());
                return;
            }
            NativeImage square;
            try (NativeImage raw = NativeImage.read(NativeImage.Format.RGBA, new ByteArrayInputStream(resp.body()))) {
                square = toSquare(raw);
            } catch (Exception e) {
                LOGGER.debug("[DungeonTrain] custom emoji {} unreadable", id, e);
                return;
            }
            Minecraft.getInstance().execute(() -> install(id, square));
        }).exceptionally(t -> {
            LOGGER.debug("[DungeonTrain] custom emoji {} fetch failed: {}", id, t.toString());
            return null;
        });
    }

    private static void install(String id, NativeImage image) {
        if (SLOT_BY_ID.containsKey(id) || nextSlot >= FIRST_SLOT + MAX_SLOTS) {
            image.close();
            return;
        }
        int slot = nextSlot++;
        IMAGE_BY_SLOT.put(slot, image); // image first: the slot must never be visible without one
        SLOT_BY_ID.put(id, slot);
        for (Consumer<String> l : LISTENERS) {
            try {
                l.accept(id);
            } catch (RuntimeException e) {
                LOGGER.warn("[DungeonTrain] custom emoji listener failed", e);
            }
        }
    }

    /** {@code raw} fitted into a {@link #SIZE}² transparent square, centred, scaled down (nearest) if larger. */
    private static NativeImage toSquare(NativeImage raw) {
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, true);
        int w = raw.getWidth();
        int h = raw.getHeight();
        float scale = Math.min(1f, Math.min((float) SIZE / w, (float) SIZE / h));
        int dw = Math.max(1, Math.round(w * scale));
        int dh = Math.max(1, Math.round(h * scale));
        int ox = (SIZE - dw) / 2;
        int oy = (SIZE - dh) / 2;
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int sx = Math.min(w - 1, (int) (x / scale));
                int sy = Math.min(h - 1, (int) (y / scale));
                out.setPixelRGBA(ox + x, oy + y, raw.getPixelRGBA(sx, sy));
            }
        }
        return out;
    }
}
