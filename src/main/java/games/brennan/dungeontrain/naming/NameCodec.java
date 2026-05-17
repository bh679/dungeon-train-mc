package games.brennan.dungeontrain.naming;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gson-backed deserializer for the three JSON schemas under
 * {@code data/dungeontrain/naming/} — pools, chains, and selectors.
 * Mirrors {@code RandomBookCodec}'s forgiving style: optional fields take
 * sensible defaults so a minimal valid file still parses.
 *
 * <p>One bad file is logged by {@link NameRegistry} and skipped — a single
 * malformed pool can't kill the whole naming subsystem.</p>
 */
public final class NameCodec {

    private NameCodec() {}

    public static final class NameParseException extends Exception {
        public NameParseException(String msg) { super(msg); }
        public NameParseException(String msg, Throwable cause) { super(msg, cause); }
    }

    public static NamePool parsePool(InputStream in, ResourceLocation fallbackId) throws NameParseException {
        JsonObject root = readRoot(in);
        ResourceLocation id = idOrFallback(root, fallbackId);
        List<NamePool.PoolEntry> entries = new ArrayList<>();
        JsonElement entriesEl = root.get("entries");
        if (entriesEl == null || !entriesEl.isJsonArray()) {
            throw new NameParseException("pool missing 'entries' array");
        }
        for (JsonElement el : entriesEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            JsonElement textEl = obj.get("text");
            if (textEl == null || !textEl.isJsonPrimitive()) continue;
            String text = textEl.getAsString();
            if (text.isEmpty()) continue;
            entries.add(new NamePool.PoolEntry(text, readResourceList(obj.get("item_types"))));
        }
        return new NamePool(id, List.copyOf(entries));
    }

    public static NameChain parseChain(InputStream in, ResourceLocation fallbackId) throws NameParseException {
        JsonObject root = readRoot(in);
        ResourceLocation id = idOrFallback(root, fallbackId);
        List<NameSegment> segments = new ArrayList<>();
        JsonElement segEl = root.get("segments");
        if (segEl == null || !segEl.isJsonArray()) {
            throw new NameParseException("chain missing 'segments' array");
        }
        for (JsonElement el : segEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            List<NameSegment.WeightedRef> refs = new ArrayList<>();
            JsonElement refsEl = obj.get("refs");
            if (refsEl != null && refsEl.isJsonArray()) {
                for (JsonElement r : refsEl.getAsJsonArray()) {
                    if (!r.isJsonObject()) continue;
                    JsonObject ro = r.getAsJsonObject();
                    JsonElement refIdEl = ro.get("ref");
                    if (refIdEl == null || !refIdEl.isJsonPrimitive()) continue;
                    ResourceLocation refId = ResourceLocation.tryParse(refIdEl.getAsString());
                    if (refId == null) continue;
                    float w = ro.has("weight") ? ro.get("weight").getAsFloat() : 1f;
                    if (w <= 0f) continue;
                    refs.add(new NameSegment.WeightedRef(refId, w));
                }
            }
            float chance = obj.has("chance") ? obj.get("chance").getAsFloat() : 1f;
            String connection = obj.has("connection") ? obj.get("connection").getAsString() : "";
            boolean newline = obj.has("newline") && obj.get("newline").getAsBoolean();
            segments.add(new NameSegment(List.copyOf(refs), chance, connection, newline));
        }
        return new NameChain(id, List.copyOf(segments));
    }

    public static NameSelector parseSelector(InputStream in, ResourceLocation fallbackId) throws NameParseException {
        JsonObject root = readRoot(in);
        ResourceLocation id = idOrFallback(root, fallbackId);
        JsonElement appliesEl = root.get("applies_to");
        if (appliesEl == null || !appliesEl.isJsonPrimitive()) {
            throw new NameParseException("selector missing 'applies_to'");
        }
        ResourceLocation appliesTo = ResourceLocation.tryParse(appliesEl.getAsString());
        if (appliesTo == null) {
            throw new NameParseException("selector 'applies_to' is not a valid tag id");
        }
        Map<String, ResourceLocation> tiers = new LinkedHashMap<>();
        JsonElement tiersEl = root.get("tiers");
        if (tiersEl == null || !tiersEl.isJsonObject()) {
            throw new NameParseException("selector missing 'tiers' object");
        }
        for (Map.Entry<String, JsonElement> e : tiersEl.getAsJsonObject().entrySet()) {
            if (!e.getValue().isJsonPrimitive()) continue;
            ResourceLocation chainId = ResourceLocation.tryParse(e.getValue().getAsString());
            if (chainId == null) continue;
            tiers.put(e.getKey(), chainId);
        }
        return new NameSelector(id, appliesTo, Map.copyOf(tiers));
    }

    private static JsonObject readRoot(InputStream in) throws NameParseException {
        try {
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) throw new NameParseException("root is not a JSON object");
            return root.getAsJsonObject();
        } catch (NameParseException e) {
            throw e;
        } catch (Exception e) {
            throw new NameParseException("invalid JSON: " + e.getMessage(), e);
        }
    }

    private static ResourceLocation idOrFallback(JsonObject root, ResourceLocation fallback) {
        JsonElement el = root.get("id");
        if (el != null && el.isJsonPrimitive()) {
            ResourceLocation parsed = ResourceLocation.tryParse(el.getAsString());
            if (parsed != null) return parsed;
        }
        return fallback;
    }

    private static List<ResourceLocation> readResourceList(JsonElement el) {
        if (el == null || !el.isJsonArray()) return List.of();
        List<ResourceLocation> out = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) continue;
            ResourceLocation rl = ResourceLocation.tryParse(item.getAsString());
            if (rl != null) out.add(rl);
        }
        return List.copyOf(out);
    }
}
