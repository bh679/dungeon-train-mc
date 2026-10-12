package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The advancements a photo earned its photographer, carried on the photograph itself so Exposure's
 * viewer can show them (client/PhotoTributeButtons): a found photo gets them from the relay's pool,
 * a fresh print from the shot's {@link PhotoSubjectTally}. Stored in the frame's extra data as a list
 * of {@code {id, icon, frame}} — the icon item and frame type are resolved on the SERVER, because the
 * client is only sent the photo advancements it has earned (they are all hidden), so it could not
 * draw the icon of one it hasn't.
 */
public final class PhotoAdvancementBadges {

    /** Frame extra-data key: a list of {@code {id, icon, frame}} compounds. */
    public static final String KEY = "dt_photo_advancements";
    static final String ID = "id";
    static final String ICON = "icon";
    static final String FRAME = "frame";
    /** The most badges a photo carries — the relay keeps the same cap per upload. */
    static final int MAX = 20;
    private static final String ID_PREFIX = "dungeontrain:";

    /** One badge: the advancement, the item its icon shows, and its frame (task / goal / challenge). */
    public record Badge(String id, String icon, String frame) {}

    private PhotoAdvancementBadges() {}

    /** The relay's {@code advancements} array → its well-formed DT ids, deduped, in order, capped. */
    static List<String> idsFrom(JsonArray array) {
        Set<String> out = new LinkedHashSet<>();
        if (array == null) return List.of();
        for (JsonElement e : array) {
            if (out.size() >= MAX) break;
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) continue;
            String id = e.getAsString();
            if (id.startsWith(ID_PREFIX) && ResourceLocation.tryParse(id) != null) out.add(id);
        }
        return List.copyOf(out);
    }

    /**
     * Badges for {@code ids}, each resolved through {@code resolve} (id → badge, or empty when the
     * advancement is unknown or has no display — those are left out).
     */
    static List<Badge> resolveAll(List<String> ids, Function<String, Optional<Badge>> resolve) {
        List<Badge> out = new ArrayList<>();
        for (String id : ids) {
            if (out.size() >= MAX) break;
            resolve.apply(id).ifPresent(out::add);
        }
        return List.copyOf(out);
    }

    /** Resolves an advancement's icon and frame from the server's own advancement data. */
    public static Optional<Badge> resolve(MinecraftServer server, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (server == null || rl == null) return Optional.empty();
        AdvancementHolder holder = server.getAdvancements().get(rl);
        if (holder == null) return Optional.empty();
        Optional<DisplayInfo> display = holder.value().display();
        if (display.isEmpty()) return Optional.empty();
        String icon = BuiltInRegistries.ITEM.getKey(display.get().getIcon().getItem()).toString();
        return Optional.of(new Badge(id, icon, display.get().getType().getSerializedName()));
    }

    /** {@code ids} resolved on {@code server} — the badges to write onto a photograph. */
    public static List<Badge> forIds(MinecraftServer server, List<String> ids) {
        return resolveAll(ids, id -> resolve(server, id));
    }

    /** Writes {@code badges} into a frame's extra data (nothing when there are none). */
    public static void write(CompoundTag extraData, List<Badge> badges) {
        if (badges == null || badges.isEmpty()) return;
        ListTag list = new ListTag();
        for (Badge b : badges) {
            CompoundTag t = new CompoundTag();
            t.putString(ID, b.id());
            t.putString(ICON, b.icon());
            t.putString(FRAME, b.frame());
            list.add(t);
        }
        extraData.put(KEY, list);
    }

    /** The badges a frame's extra data carries, in order; empty for photos taken before badges existed. */
    public static List<Badge> read(CompoundTag extraData) {
        if (extraData == null || !extraData.contains(KEY, Tag.TAG_LIST)) return List.of();
        ListTag list = extraData.getList(KEY, Tag.TAG_COMPOUND);
        List<Badge> out = new ArrayList<>();
        for (int i = 0; i < list.size() && out.size() < MAX; i++) {
            CompoundTag t = list.getCompound(i);
            String id = t.getString(ID);
            if (id.isEmpty()) continue;
            out.add(new Badge(id, t.getString(ICON), t.getString(FRAME)));
        }
        return List.copyOf(out);
    }
}
