package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable outbox for translation submissions, so a translator's work survives a relay outage or
 * an offline session instead of being silently lost.
 *
 * <p>The client-side sibling of {@code ChatOutbox}, and for the same reason: the translation
 * editor opens at the title screen, where there is no Minecraft server to retry from. Same
 * posture throughout — atomic tmp+rename write, bounded by count and age, and delivery is
 * <b>at-least-once</b>: an item is removed only once the relay confirms a 2xx, so the cost of a
 * crash mid-flush is a duplicate rather than a lost submission. The relay dedupes by content
 * hash, so that duplicate is free.</p>
 *
 * <p>A non-retryable 4xx is dropped rather than retried forever — a payload the relay will never
 * accept would otherwise sit at the head of the queue blocking everything behind it.</p>
 */
public final class TranslationOutbox {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final TranslationOutbox INSTANCE = new TranslationOutbox();

    public static TranslationOutbox get() {
        return INSTANCE;
    }

    /** Bounded so a permanently-offline client cannot grow the file without limit. */
    static final int MAX_ITEMS = 100;
    /** A submission this old is stale — the locale has almost certainly moved on. */
    static final long MAX_AGE_MS = java.time.Duration.ofDays(30).toMillis();
    private static final String FILE_NAME = "dungeontrain-translation-outbox.json";

    /** key → item, insertion-ordered so the oldest submission is delivered first. */
    private final Map<String, Item> pending = new LinkedHashMap<>();
    /** Keys currently in flight, so overlapping flushes cannot double-send one item. */
    private final java.util.Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private Path file;
    private boolean loaded;

    private TranslationOutbox() {}

    /** One queued submission. {@code body} is the exact JSON the relay will receive. */
    private record Item(String key, String body, long createdAtMs) {}

    /** Queue one submission and try to deliver it now. Never throws. */
    public synchronized void submit(String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        load();
        String key = UUID.randomUUID().toString();
        pending.put(key, new Item(key, body, System.currentTimeMillis()));
        trim();
        save();
        flush();
    }

    /**
     * Everything still waiting to go, as list rows.
     *
     * <p>Read back out of each item's stored body rather than kept as separate fields: the body
     * is the exact JSON the relay will receive, so it is already the authoritative record of what
     * was submitted, and a second copy could only drift from it.</p>
     */
    public synchronized List<TranslationSubmission> queued() {
        load();
        List<TranslationSubmission> out = new ArrayList<>();
        for (Item item : pending.values()) {
            try {
                JsonObject body = JsonParser.parseString(item.body()).getAsJsonObject();
                out.add(TranslationSubmission.queued(
                    item.createdAtMs(),
                    body.has("locale") ? body.get("locale").getAsString() : "",
                    body.has("translator") ? body.get("translator").getAsString() : "",
                    body.has("units") ? body.getAsJsonArray("units").size() : 0));
            } catch (Exception e) {
                LOGGER.debug("[DungeonTrain] Translations: unreadable queued item — {}", e.toString());
            }
        }
        return out;
    }

    /** How many submissions are still waiting — the screen shows this. */
    public synchronized int pendingCount() {
        load();
        return pending.size();
    }

    /**
     * Try to deliver everything queued. Safe to call repeatedly; items already in flight are
     * skipped, and each item is removed only after the relay confirms it.
     */
    public void flush() {
        List<Item> batch;
        synchronized (this) {
            load();
            trim();
            batch = new ArrayList<>();
            for (Item item : pending.values()) {
                if (inFlight.add(item.key())) {
                    batch.add(item);
                }
            }
        }
        for (Item item : batch) {
            TranslationSubmitClient.send(item.body()).whenComplete((result, err) -> {
                try {
                    boolean drop = err == null && result != null && (result.ok() || !result.retryable());
                    if (drop) {
                        synchronized (this) {
                            pending.remove(item.key());
                            save();
                        }
                    }
                } finally {
                    inFlight.remove(item.key());
                }
            });
        }
    }

    // ---- persistence ---------------------------------------------------------

    private synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            this.file = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: no config dir for the outbox — {}", e.toString());
            return;
        }
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement arr = root.get("pending");
            if (arr != null && arr.isJsonArray()) {
                for (JsonElement el : arr.getAsJsonArray()) {
                    if (el == null || !el.isJsonObject()) {
                        continue;
                    }
                    JsonObject o = el.getAsJsonObject();
                    String key = optString(o, "key");
                    String body = optString(o, "body");
                    long createdAtMs = o.has("createdAtMs") && o.get("createdAtMs").isJsonPrimitive()
                        ? o.get("createdAtMs").getAsLong() : System.currentTimeMillis();
                    if (key != null && body != null && !body.isBlank()) {
                        pending.put(key, new Item(key, body, createdAtMs));
                    }
                }
            }
            trim();
            LOGGER.debug("[DungeonTrain] Translations: {} queued submission(s) loaded.", pending.size());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: unreadable outbox {}; starting empty.", file, e);
            pending.clear();
        }
    }

    /** Drop items past the age limit, then the oldest ones past the count limit. */
    private void trim() {
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        pending.values().removeIf(item -> item.createdAtMs() < cutoff);
        Iterator<String> it = pending.keySet().iterator();
        while (pending.size() > MAX_ITEMS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private void save() {
        Path target = this.file;
        if (target == null) {
            return;
        }
        try {
            JsonArray arr = new JsonArray();
            for (Item item : pending.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("key", item.key());
                o.addProperty("body", item.body());
                o.addProperty("createdAtMs", item.createdAtMs());
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("pending", arr);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to write the outbox {}.", target, e);
        }
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}
