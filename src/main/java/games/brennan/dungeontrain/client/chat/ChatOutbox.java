package games.brennan.dungeontrain.client.chat;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable outbox for menu-typed Discord messages, so a message survives a relay outage (or just an
 * offline launch) and is delivered on the next flush. The title-screen panel has no Minecraft server,
 * so a failed send has nowhere to retry from but the client — this is that store.
 *
 * <p>Backed by a small JSON file in the MC config dir ({@code dungeontrain-chat-outbox.json}:
 * {@code {"pending":[{key, uuid, content}]}}), written through atomically (tmp + rename) like
 * {@link games.brennan.discordpresence.reincarnation.ReincarnationOutbox}. Best-effort: a missing or
 * corrupt file yields an empty queue and never throws into the render thread.</p>
 *
 * <p><b>Delivery is at-least-once</b> — the deliberate inverse of {@code ReincarnationOutbox}'s
 * best-effort/mark-before-send posture. A queued item is removed <i>only after</i> the relay confirms a
 * 2xx, so a message is never silently lost; the cost is a rare double-post if the process dies after
 * Discord accepts the message but before this persists the removal. We prefer a duplicate over a lost
 * message for player chat. Each item carries a unique {@code key}: it dedups the queue (one logical
 * message is never enqueued or flushed twice) and is the handle a future relay-side idempotency check
 * could use to collapse the duplicate-on-crash case.</p>
 */
public final class ChatOutbox {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ChatOutbox INSTANCE = new ChatOutbox();

    public static ChatOutbox get() {
        return INSTANCE;
    }

    /** Bounded so a permanently-offline client can't grow the file without limit (oldest evicted). */
    static final int MAX_ITEMS = 200;
    private static final String FILE_NAME = "dungeontrain-chat-outbox.json";

    private final LinkedHashMap<String, Item> pending = new LinkedHashMap<>(); // key -> item (oldest first)
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();         // keys with a send in progress
    private Path file;
    private boolean loaded;

    private ChatOutbox() {}

    private record Item(String key, String uuid, String content) {}

    /**
     * Queue {@code content} for {@code uuid} and immediately try to deliver it. Called on the render
     * thread; never throws. Online → the flush delivers and removes it right away; offline → it stays
     * queued for the next {@link #flush()} (panel open / next launch).
     */
    public synchronized void submit(UUID uuid, String content) {
        if (uuid == null || content == null || content.isBlank()) {
            return;
        }
        ensureLoaded();
        String key = UUID.randomUUID().toString(); // dedup handle (see class doc); never collides
        pending.put(key, new Item(key, uuid.toString(), content));
        trim();
        save();
        flush();
    }

    /**
     * Attempt to deliver every queued message, removing each only after the relay confirms a 2xx
     * ({@link RelayChatClient#sendMessage}). No consent / unreachable relay → items stay queued. Safe to
     * call repeatedly (panel open, after submit): an in-flight key is never dispatched twice.
     */
    public void flush() {
        if (!RelayChatClient.canConnect()) {
            return;
        }
        List<Item> snapshot;
        synchronized (this) {
            ensureLoaded();
            if (pending.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(pending.values());
        }
        for (Item it : snapshot) {
            if (!inFlight.add(it.key())) {
                continue; // a prior flush is still delivering this one
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(it.uuid());
            } catch (Exception e) {
                remove(it.key()); // corrupt entry — drop it rather than wedge the queue forever
                inFlight.remove(it.key());
                continue;
            }
            RelayChatClient.sendMessage(uuid, it.content()).whenComplete((ok, t) -> {
                inFlight.remove(it.key());
                if (Boolean.TRUE.equals(ok)) {
                    remove(it.key());
                }
            });
        }
    }

    /** Test seam: number of messages still awaiting delivery. */
    public synchronized int pendingCount() {
        ensureLoaded();
        return pending.size();
    }

    private synchronized void remove(String key) {
        if (pending.remove(key) != null) {
            save();
        }
    }

    private void trim() {
        Iterator<String> it = pending.keySet().iterator();
        while (pending.size() > MAX_ITEMS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    // --- persistence (best-effort) ---

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            this.file = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        } catch (Exception e) {
            LOGGER.debug("Menu chat: could not resolve config dir for outbox: {}", e.toString());
            return;
        }
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonElement arr = obj.get("pending");
            if (arr != null && arr.isJsonArray()) {
                for (JsonElement el : arr.getAsJsonArray()) {
                    if (el == null || !el.isJsonObject()) {
                        continue;
                    }
                    JsonObject o = el.getAsJsonObject();
                    String key = optString(o, "key");
                    String uuid = optString(o, "uuid");
                    String content = optString(o, "content");
                    if (key != null && uuid != null && content != null && !content.isBlank()) {
                        pending.put(key, new Item(key, uuid, content));
                    }
                }
            }
            trim();
            LOGGER.debug("Menu chat: loaded {} queued message(s) from the outbox.", pending.size());
        } catch (Exception e) {
            LOGGER.warn("Menu chat: failed to read outbox {}; starting empty.", file, e);
            pending.clear();
        }
    }

    private void save() {
        Path target = this.file;
        if (target == null) {
            return;
        }
        try {
            JsonArray arr = new JsonArray();
            for (Item it : pending.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("key", it.key());
                o.addProperty("uuid", it.uuid());
                o.addProperty("content", it.content());
                arr.add(o);
            }
            JsonObject obj = new JsonObject();
            obj.add("pending", arr);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("Menu chat: failed to write outbox {}.", target, e);
        }
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}
