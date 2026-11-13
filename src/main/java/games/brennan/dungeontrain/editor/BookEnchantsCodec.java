package games.brennan.dungeontrain.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link BookEnchants} on disk (a loot entry's JSON) and on the clipboard (NBT). Every field is written
 * only when it differs from its default, so an entry without book enchantments looks as it always did.
 *
 * <p>JSON: {@code "enchants": {"minecraft:mending": 1}} for the exact ones, and
 * {@code "bookEnchants": {"min": 1, "max": 7, "levelMin": 3, "levelMax": 0, "list": {"minecraft:sharpness": true}}}
 * for the random ones.</p>
 */
public final class BookEnchantsCodec {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Entry JSON key for the exact enchantments, id to level. */
    public static final String JSON_EXACT = "enchants";
    /** Entry JSON key for the random ones: count range, level range, list. */
    public static final String JSON_RANDOM = "bookEnchants";
    private static final String KEY_MIN = "min";
    private static final String KEY_MAX = "max";
    private static final String KEY_LEVEL_MIN = "levelMin";
    private static final String KEY_LEVEL_MAX = "levelMax";
    private static final String KEY_LIST = "list";

    private BookEnchantsCodec() {}

    /** The book enchantments on entry object {@code entry}, or {@code null} when it has none. */
    public static @Nullable BookEnchants fromJson(JsonObject entry) {
        try {
            Map<ResourceLocation, Integer> exact = new LinkedHashMap<>();
            JsonElement ex = entry.get(JSON_EXACT);
            if (ex != null && ex.isJsonObject()) {
                for (var e : ex.getAsJsonObject().entrySet()) {
                    ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                    if (id != null && e.getValue().isJsonPrimitive()) exact.put(id, e.getValue().getAsInt());
                }
            }
            int min = 0;
            int max = 0;
            int levelMin = BookEnchants.LEVEL_TOP;
            int levelMax = BookEnchants.LEVEL_TOP;
            Map<ResourceLocation, Boolean> list = null;
            JsonElement rnd = entry.get(JSON_RANDOM);
            if (rnd != null && rnd.isJsonObject()) {
                JsonObject o = rnd.getAsJsonObject();
                max = o.has(KEY_MAX) ? o.get(KEY_MAX).getAsInt() : 0;
                min = o.has(KEY_MIN) ? o.get(KEY_MIN).getAsInt() : max;
                if (o.has(KEY_LEVEL_MIN)) levelMin = o.get(KEY_LEVEL_MIN).getAsInt();
                if (o.has(KEY_LEVEL_MAX)) levelMax = o.get(KEY_LEVEL_MAX).getAsInt();
                if (o.has(KEY_LIST) && o.get(KEY_LIST).isJsonObject()) {
                    list = new LinkedHashMap<>();
                    for (var e : o.getAsJsonObject(KEY_LIST).entrySet()) {
                        ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                        if (id != null && e.getValue().isJsonPrimitive()) list.put(id, e.getValue().getAsBoolean());
                    }
                }
            }
            BookEnchants enchants = new BookEnchants(exact, min, max, levelMin, levelMax, list);
            return enchants.isEmpty() ? null : enchants;
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Loot book enchantments: malformed JSON, dropping them — {}", e.toString());
            return null;
        }
    }

    /** {@code , "enchants": {...}, "bookEnchants": {...}} for the hand-built store writers. */
    public static void appendJson(StringBuilder sb, @Nullable BookEnchants enchants) {
        if (enchants == null || enchants.isEmpty()) return;
        if (!enchants.exact().isEmpty()) {
            sb.append(", \"").append(JSON_EXACT).append("\": {");
            appendMap(sb, enchants.exact());
            sb.append('}');
        }
        if (enchants.hasRandom()) {
            sb.append(", \"").append(JSON_RANDOM).append("\": { \"").append(KEY_MIN).append("\": ")
                .append(enchants.randomMin()).append(", \"").append(KEY_MAX).append("\": ")
                .append(enchants.randomMax());
            if (!enchants.isTopLevelOnly()) {
                sb.append(", \"").append(KEY_LEVEL_MIN).append("\": ").append(enchants.levelMin())
                    .append(", \"").append(KEY_LEVEL_MAX).append("\": ").append(enchants.levelMax());
            }
            if (enchants.list() != null) {
                sb.append(", \"").append(KEY_LIST).append("\": {");
                appendMap(sb, enchants.list());
                sb.append('}');
            }
            sb.append(" }");
        }
    }

    private static void appendMap(StringBuilder sb, Map<ResourceLocation, ?> map) {
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) sb.append(", ");
            sb.append('"').append(e.getKey()).append("\": ").append(e.getValue());
            first = false;
        }
    }

    public static CompoundTag toNbt(BookEnchants enchants) {
        CompoundTag tag = new CompoundTag();
        CompoundTag exact = new CompoundTag();
        enchants.exact().forEach((id, level) -> exact.putInt(id.toString(), level));
        tag.put(JSON_EXACT, exact);
        tag.putInt(KEY_MIN, enchants.randomMin());
        tag.putInt(KEY_MAX, enchants.randomMax());
        tag.putInt(KEY_LEVEL_MIN, enchants.levelMin());
        tag.putInt(KEY_LEVEL_MAX, enchants.levelMax());
        if (enchants.list() != null) {
            CompoundTag list = new CompoundTag();
            enchants.list().forEach((id, on) -> list.putBoolean(id.toString(), on));
            tag.put(KEY_LIST, list);
        }
        return tag;
    }

    public static @Nullable BookEnchants fromNbt(@Nullable CompoundTag tag) {
        if (tag == null) return null;
        Map<ResourceLocation, Integer> exact = new LinkedHashMap<>();
        CompoundTag ex = tag.getCompound(JSON_EXACT);
        for (String key : ex.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id != null && ex.contains(key, Tag.TAG_INT)) exact.put(id, ex.getInt(key));
        }
        Map<ResourceLocation, Boolean> list = null;
        if (tag.contains(KEY_LIST, Tag.TAG_COMPOUND)) {
            list = new LinkedHashMap<>();
            CompoundTag l = tag.getCompound(KEY_LIST);
            for (String key : l.getAllKeys()) {
                ResourceLocation id = ResourceLocation.tryParse(key);
                if (id != null) list.put(id, l.getBoolean(key));
            }
        }
        BookEnchants enchants = new BookEnchants(exact, tag.getInt(KEY_MIN), tag.getInt(KEY_MAX),
            tag.getInt(KEY_LEVEL_MIN), tag.getInt(KEY_LEVEL_MAX), list);
        return enchants.isEmpty() ? null : enchants;
    }
}
