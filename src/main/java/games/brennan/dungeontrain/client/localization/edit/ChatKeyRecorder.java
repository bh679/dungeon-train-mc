package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Remembers which lang keys this install has seen arriving in chat, so the translation preview knows
 * a string is read there even when its key does not say so.
 *
 * <p>The chat-side twin of {@link ButtonKeyRecorder}: every message — player chat and system lines —
 * is walked for translatable parts, arguments and siblings included, and the editor's own keys
 * ({@link ButtonKeyRecorder#isOurs}) are written down. Best-effort like the other stores here: an
 * unreadable file reads as "seen nothing", which only narrows the preview's choice of views.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ChatKeyRecorder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String FILE = "chat-keys.json";
    private static final int MAX_ENTRIES = 5000;
    /** Nesting guard for pathological components. */
    private static final int MAX_DEPTH = 16;

    private static Set<String> keys;

    private ChatKeyRecorder() {}

    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        Set<String> found = new LinkedHashSet<>();
        collect(event.getMessage(), found, 0);
        if (!found.isEmpty() && record(found)) {
            save();
        }
    }

    /** Whether {@code key} has been seen in a chat message. */
    public static synchronized boolean seen(String key) {
        return key != null && keys().contains(key);
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

    private static synchronized boolean record(Set<String> found) {
        Set<String> set = keys();
        boolean changed = false;
        for (String key : found) {
            if (set.size() >= MAX_ENTRIES) {
                break;
            }
            changed |= set.add(key);
        }
        return changed;
    }

    private static Set<String> keys() {
        if (keys == null) {
            keys = load();
        }
        return keys;
    }

    private static Path file() {
        try {
            return TranslationOverrideStore.root().resolve(FILE);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: could not resolve config dir — {}", e.toString());
            return null;
        }
    }

    private static Set<String> load() {
        Set<String> out = new LinkedHashSet<>();
        Path file = file();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (root.isJsonArray()) {
                for (JsonElement entry : root.getAsJsonArray()) {
                    if (out.size() < MAX_ENTRIES && entry.isJsonPrimitive()
                        && ButtonKeyRecorder.isOurs(entry.getAsString())) {
                        out.add(entry.getAsString());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to read {}; ignoring it — {}", file, e.toString());
        }
        return out;
    }

    private static synchronized void save() {
        Path file = file();
        if (file == null) {
            return;
        }
        try {
            JsonArray array = new JsonArray();
            keys().forEach(array::add);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, array.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to write {} — {}", file, e.toString());
        }
    }
}
