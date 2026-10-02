package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Remembers which lang keys this install has seen arriving in chat or on the action bar, so the
 * translation preview knows a string is read there even when its key does not say so.
 *
 * <p>The message-side twin of {@link ButtonKeyRecorder}: every incoming message is walked for
 * translatable parts, arguments and siblings included, and the editor's own keys
 * ({@link ButtonKeyRecorder#isOurs}) are written down — as chat, or, for an overlay message, as the
 * action bar, which arrives on the same event and must never make a string look like chat. For the
 * action bar the last whole message each key appeared in is kept too, so the preview redraws that
 * exact line with only the key's text changed.</p>
 *
 * <p>Best-effort like the other stores here: an unreadable file reads as "seen nothing", which only
 * narrows the preview's choice of views. The first file format, a bare array of chat keys, still
 * loads.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ChatKeyRecorder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String FILE = "chat-keys.json";
    private static final String CHAT = "chat";
    private static final String ACTION_BAR = "action_bar";
    private static final int MAX_ENTRIES = 5000;
    /** Nesting guard for pathological components. */
    private static final int MAX_DEPTH = 16;

    /** Keys seen in chat; null until first read off disk. */
    private static Set<String> chat;
    /** Action-bar key → the last message it appeared in, as component JSON. */
    private static Map<String, String> actionBar;

    private ChatKeyRecorder() {}

    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        Set<String> found = new LinkedHashSet<>();
        collect(event.getMessage(), found, 0);
        if (found.isEmpty()) {
            return;
        }
        boolean overlay = event instanceof ClientChatReceivedEvent.System system && system.isOverlay();
        if (overlay ? recordActionBar(found, event.getMessage()) : recordChat(found)) {
            save();
        }
    }

    /** Whether {@code key} has been seen in a chat message. */
    public static synchronized boolean seen(String key) {
        load();
        return key != null && chat.contains(key);
    }

    /** Whether {@code key} has been seen in an action-bar message. */
    public static synchronized boolean seenInActionBar(String key) {
        load();
        return key != null && actionBar.containsKey(key);
    }

    /** The last action-bar message {@code key} appeared in, or null. */
    public static synchronized Component actionBarMessage(String key) {
        load();
        String json = key == null ? null : actionBar.get(key);
        return json == null ? null : RecordedComponents.fromJson(json);
    }

    private static void collect(Component component, Set<String> out, int depth) {
        if (component == null || depth > MAX_DEPTH) {
            return;
        }
        if (component.getContents() instanceof TranslatableContents contents) {
            if (ButtonKeyRecorder.isOurs(contents.getKey())) {
                out.add(contents.getKey());
            }
            for (Object arg : contents.getArgs()) {
                if (arg instanceof Component nested) {
                    collect(nested, out, depth + 1);
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            collect(sibling, out, depth + 1);
        }
    }

    private static synchronized boolean recordChat(Set<String> found) {
        load();
        boolean changed = false;
        for (String key : found) {
            if (chat.size() >= MAX_ENTRIES) {
                break;
            }
            changed |= chat.add(key);
        }
        return changed;
    }

    private static synchronized boolean recordActionBar(Set<String> found, Component message) {
        load();
        String json = RecordedComponents.toJson(message);
        if (json == null) {
            return false;
        }
        boolean changed = false;
        for (String key : found) {
            if (actionBar.size() < MAX_ENTRIES || actionBar.containsKey(key)) {
                changed |= !json.equals(actionBar.put(key, json));
            }
        }
        return changed;
    }

    private static Path file() {
        try {
            return TranslationOverrideStore.root().resolve(FILE);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: could not resolve config dir — {}", e.toString());
            return null;
        }
    }

    /** Reads the file once. */
    private static void load() {
        if (chat != null) {
            return;
        }
        chat = new LinkedHashSet<>();
        actionBar = new LinkedHashMap<>();
        Path file = file();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            JsonObject obj = root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
            JsonArray chatKeys = root.isJsonArray() ? root.getAsJsonArray()
                : obj.has(CHAT) ? obj.getAsJsonArray(CHAT) : new JsonArray();
            for (JsonElement entry : chatKeys) {
                if (chat.size() < MAX_ENTRIES && entry.isJsonPrimitive()
                    && ButtonKeyRecorder.isOurs(entry.getAsString())) {
                    chat.add(entry.getAsString());
                }
            }
            if (obj.has(ACTION_BAR)) {
                for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject(ACTION_BAR).entrySet()) {
                    if (actionBar.size() < MAX_ENTRIES && ButtonKeyRecorder.isOurs(entry.getKey())
                        && entry.getValue().isJsonPrimitive()) {
                        actionBar.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to read {}; ignoring it — {}", file, e.toString());
        }
    }

    private static synchronized void save() {
        Path file = file();
        if (file == null) {
            return;
        }
        try {
            JsonArray chatKeys = new JsonArray();
            chat.forEach(chatKeys::add);
            JsonObject overlay = new JsonObject();
            actionBar.forEach(overlay::addProperty);
            JsonObject root = new JsonObject();
            root.add(CHAT, chatKeys);
            root.add(ACTION_BAR, overlay);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to write {} — {}", file, e.toString());
        }
    }
}
