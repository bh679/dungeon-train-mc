package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remembers the item tooltips the editor's keys have been seen in, so the translation preview can
 * show a tooltip line — or an item's name — inside the real tooltip it belongs to.
 *
 * <p>The first time one of our keys turns up in a tooltip, the item and every line of that tooltip
 * are kept; the preview then redraws exactly those lines with only the key's text changed. Runs at
 * the lowest priority so lines other mods add are in the picture too. Cheap per frame: a key already
 * recorded costs one map lookup.</p>
 *
 * <p>Best-effort like the other stores here: an unreadable file reads as "seen nothing".</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ItemTooltipRecorder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String FILE = "item-tooltips.json";
    private static final int MAX_ENTRIES = 3000;
    private static final int MAX_DEPTH = 16;

    /** A tooltip as it was seen: which item, and its lines. */
    public record Snapshot(ResourceLocation item, List<Component> lines) {}

    /** key → {item, lines as component JSON}; null until first read off disk. */
    private static Map<String, JsonObject> snapshots;

    private ItemTooltipRecorder() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTooltip(ItemTooltipEvent event) {
        Set<String> keys = new LinkedHashSet<>();
        for (Component line : event.getToolTip()) {
            collect(line, keys, 0);
        }
        if (keys.isEmpty() || !anyNew(keys)) {
            return;
        }
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("item", BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem()).toString());
        JsonArray lines = new JsonArray();
        for (Component line : event.getToolTip()) {
            String json = RecordedComponents.toJson(line);
            if (json == null) {
                return; // a tooltip we cannot keep whole is not kept at all
            }
            lines.add(json);
        }
        snapshot.add("lines", lines);
        if (record(keys, snapshot)) {
            save();
        }
    }

    /** The tooltip {@code key} was first seen in, or null. */
    public static synchronized Snapshot snapshotFor(String key) {
        JsonObject obj = key == null ? null : snapshots().get(key);
        if (obj == null) {
            return null;
        }
        ResourceLocation item = ResourceLocation.tryParse(obj.get("item").getAsString());
        List<Component> lines = new ArrayList<>();
        for (JsonElement line : obj.getAsJsonArray("lines")) {
            Component c = RecordedComponents.fromJson(line.getAsString());
            if (c == null) {
                return null;
            }
            lines.add(c);
        }
        return item == null ? null : new Snapshot(item, lines);
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

    private static synchronized boolean anyNew(Set<String> keys) {
        Map<String, JsonObject> map = snapshots();
        return keys.stream().anyMatch(k -> !map.containsKey(k));
    }

    private static synchronized boolean record(Set<String> keys, JsonObject snapshot) {
        Map<String, JsonObject> map = snapshots();
        boolean changed = false;
        for (String key : keys) {
            if (!map.containsKey(key) && map.size() < MAX_ENTRIES) {
                map.put(key, snapshot);
                changed = true;
            }
        }
        return changed;
    }

    private static Map<String, JsonObject> snapshots() {
        if (snapshots == null) {
            snapshots = load();
        }
        return snapshots;
    }

    private static Path file() {
        try {
            return TranslationOverrideStore.root().resolve(FILE);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: could not resolve config dir — {}", e.toString());
            return null;
        }
    }

    private static Map<String, JsonObject> load() {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        Path file = file();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (root.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                    JsonElement value = entry.getValue();
                    if (out.size() < MAX_ENTRIES && ButtonKeyRecorder.isOurs(entry.getKey())
                        && value.isJsonObject() && value.getAsJsonObject().has("item")
                        && value.getAsJsonObject().has("lines")) {
                        out.put(entry.getKey(), value.getAsJsonObject());
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
            JsonObject root = new JsonObject();
            snapshots().forEach(root::add);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to write {} — {}", file, e.toString());
        }
    }
}
