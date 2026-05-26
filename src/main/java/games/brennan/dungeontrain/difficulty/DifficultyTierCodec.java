package games.brennan.dungeontrain.difficulty;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Gson-backed deserializer for {@code data/dungeontrain/difficulty/tiers.json}.
 *
 * <p>Forgiving by design — missing optional fields collapse to sane defaults
 * (empty armor lists, {@code slotChance=0}, no effects, no enchants). A single
 * bad block inside a tier is skipped rather than failing the whole file, so a
 * malformed weapon entry doesn't take down the rest of the curve.</p>
 *
 * <p>The codec does not resolve item ids against the vanilla registry — that
 * happens at apply time, so this codec is safe to call from unit tests without
 * a server bootstrap.</p>
 */
public final class DifficultyTierCodec {

    private DifficultyTierCodec() {}

    /** Parse an ordered tier list. Caller owns the stream. */
    public static List<DifficultyTier> parse(InputStream in) throws DifficultyParseException {
        JsonElement rootEl;
        try {
            rootEl = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new DifficultyParseException("invalid JSON: " + e.getMessage(), e);
        }
        if (!rootEl.isJsonObject()) throw new DifficultyParseException("root is not a JSON object");
        JsonObject root = rootEl.getAsJsonObject();

        if (!root.has("tiers") || !root.get("tiers").isJsonArray()) {
            throw new DifficultyParseException("missing or non-array 'tiers' field");
        }
        JsonArray tiersArr = root.getAsJsonArray("tiers");
        List<DifficultyTier> tiers = new ArrayList<>(tiersArr.size());
        int idx = 0;
        for (JsonElement el : tiersArr) {
            if (!el.isJsonObject()) {
                throw new DifficultyParseException("tier at index " + idx + " is not an object");
            }
            tiers.add(parseTier(el.getAsJsonObject(), idx));
            idx++;
        }
        if (tiers.isEmpty()) throw new DifficultyParseException("tiers list is empty");
        return Collections.unmodifiableList(tiers);
    }

    private static DifficultyTier parseTier(JsonObject obj, int idx) {
        String name = optionalString(obj, "name", "tier" + idx);
        DifficultyTier.ArmorSet armor = parseArmorSet(obj.get("armor"));
        DifficultyTier.WeaponSet weapon = parseWeaponSet(obj.get("weapon"));
        List<DifficultyTier.EffectSpec> effects = parseEffects(obj.get("effects"));
        DifficultyTier.EnchantSpec enchant = parseEnchantSpec(obj.get("enchant"));
        return new DifficultyTier(name, armor, weapon, effects, enchant);
    }

    private static DifficultyTier.ArmorSet parseArmorSet(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return new DifficultyTier.ArmorSet(
                    List.of(), List.of(), List.of(), List.of(), 0.0);
        }
        JsonObject obj = el.getAsJsonObject();
        return new DifficultyTier.ArmorSet(
                parseWeightedItems(obj.get("helmet")),
                parseWeightedItems(obj.get("chestplate")),
                parseWeightedItems(obj.get("leggings")),
                parseWeightedItems(obj.get("boots")),
                optionalDouble(obj, "slotChance", 0.0));
    }

    private static DifficultyTier.WeaponSet parseWeaponSet(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return new DifficultyTier.WeaponSet(List.of(), 0.0);
        }
        JsonObject obj = el.getAsJsonObject();
        return new DifficultyTier.WeaponSet(
                parseWeightedItems(obj.get("mainhand")),
                optionalDouble(obj, "chance", 0.0));
    }

    private static List<DifficultyTier.EffectSpec> parseEffects(JsonElement el) {
        if (el == null || !el.isJsonArray()) return List.of();
        JsonArray arr = el.getAsJsonArray();
        List<DifficultyTier.EffectSpec> out = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            ResourceLocation id = parseId(o.get("id"));
            if (id == null) continue;
            int amplifier = optionalInt(o, "amplifier", 0);
            int duration = optionalInt(o, "durationTicks", -1);
            double chance = optionalDouble(o, "chance", 0.0);
            out.add(new DifficultyTier.EffectSpec(id, amplifier, duration, chance));
        }
        return Collections.unmodifiableList(out);
    }

    private static DifficultyTier.EnchantSpec parseEnchantSpec(JsonElement el) {
        if (el == null || !el.isJsonObject()) return DifficultyTier.EnchantSpec.NONE;
        JsonObject o = el.getAsJsonObject();
        int maxLevel = optionalInt(o, "maxLevel", 0);
        double chance = optionalDouble(o, "chance", 0.0);
        boolean treasure = optionalBool(o, "treasure", false);
        return new DifficultyTier.EnchantSpec(maxLevel, chance, treasure);
    }

    private static List<DifficultyTier.WeightedItem> parseWeightedItems(JsonElement el) {
        if (el == null || !el.isJsonArray()) return List.of();
        JsonArray arr = el.getAsJsonArray();
        List<DifficultyTier.WeightedItem> out = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            ResourceLocation item = parseId(o.get("item"));
            if (item == null) continue;
            int weight = Math.max(1, optionalInt(o, "weight", 1));
            out.add(new DifficultyTier.WeightedItem(item, weight));
        }
        return Collections.unmodifiableList(out);
    }

    private static ResourceLocation parseId(JsonElement el) {
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) return null;
        String s = el.getAsString();
        if (s.isEmpty()) return null;
        try {
            return ResourceLocation.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String optionalString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isString()) {
            return fallback;
        }
        String v = obj.get(key).getAsString();
        return v.isEmpty() ? fallback : v;
    }

    private static int optionalInt(JsonObject obj, String key, int fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double optionalDouble(JsonObject obj, String key, double fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsDouble();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean optionalBool(JsonObject obj, String key, boolean fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    public static final class DifficultyParseException extends Exception {
        public DifficultyParseException(String message) { super(message); }
        public DifficultyParseException(String message, Throwable cause) { super(message, cause); }
    }
}
