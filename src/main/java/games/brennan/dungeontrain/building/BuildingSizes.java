package games.brennan.dungeontrain.building;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How big each building is, without a {@code ServerLevel} to ask — the editor's plot layout and hit-testing
 * need it from a bare block position, as {@code PortalRoomSizes} does for rooms.
 *
 * <p>A building's size lives in its template: {@link #sizeOf} reads the {@code size} field out of the NBT
 * once and remembers it. {@link #setPending} is the editor's override while the author resizes a plot
 * before saving; a save {@link #settle}s it.</p>
 */
public final class BuildingSizes {

    private static final Map<String, Vec3i> SIZES = new ConcurrentHashMap<>();
    private static final Map<String, Vec3i> PENDING = new ConcurrentHashMap<>();

    private BuildingSizes() {}

    /** The size {@code name}'s plot should be: a pending resize, else its template's, else the default. */
    public static Vec3i sizeOf(String name) {
        if (name == null) return Buildings.DEFAULT_SIZE;
        Vec3i pending = PENDING.get(name);
        if (pending != null) return pending;
        Vec3i known = SIZES.computeIfAbsent(name, n -> BuildingStore.readTag(n)
            .map(BuildingSizes::sizeIn).orElse(Buildings.DEFAULT_SIZE));
        return Buildings.clamp(known);
    }

    /** Resize {@code name}'s plot ahead of a save. Clamped to the building caps. */
    public static Vec3i setPending(String name, Vec3i size) {
        Vec3i clamped = Buildings.clamp(size);
        PENDING.put(name, clamped);
        return clamped;
    }

    /** True when {@code name} has an unsaved resize. */
    public static boolean hasPending(String name) {
        return PENDING.containsKey(name);
    }

    /** A save wrote {@code name} at {@code size}: the template is the authority again. */
    public static void settle(String name, Vec3i size) {
        SIZES.put(name, Buildings.clamp(size));
        PENDING.remove(name);
    }

    /** Forget {@code name} — a reset or delete; the next ask measures the file again. */
    public static void forget(String name) {
        SIZES.remove(name);
        PENDING.remove(name);
    }

    public static void clear() {
        SIZES.clear();
        PENDING.clear();
    }

    /** The {@code size} a saved structure declares, or the default when the tag has none. */
    public static Vec3i sizeIn(CompoundTag structure) {
        ListTag size = structure.getList("size", Tag.TAG_INT);
        if (size.size() != 3) return Buildings.DEFAULT_SIZE;
        return new Vec3i(size.getInt(0), size.getInt(1), size.getInt(2));
    }
}
