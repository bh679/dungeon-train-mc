package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.portal.PortalRoomTemplateSize;
import games.brennan.dungeontrain.track.variant.TrackKind;
import net.minecraft.core.Vec3i;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How big each named prefab is, without needing a {@code ServerLevel} to ask.
 *
 * <p>The prefab twin of {@link games.brennan.dungeontrain.portal.PortalRoomSizes}, for the same
 * reason: a prefab's size lives in its template and nowhere else ({@link TrackKind#freeSize()}),
 * but the editor's plot layout has to size a prefab's plot from a block position with no level to
 * load templates through. Every load records the size here; the layout reads it back.</p>
 *
 * <p>A name with no entry and no template is a plot that has never been saved — it opens at
 * {@link TrackKind#PREFAB_DEFAULT_SIZE}, or at whatever the author asked for via {@link #pending}
 * ({@code /dt editor prefabs new <name> <L> <H> <W>}). Unlike rooms there is no clamp: a prefab
 * has no slot to fit, it is clipped to its parent at stamp time instead.</p>
 */
public final class PrefabSizes {

    private static final Map<String, Vec3i> SIZES = new ConcurrentHashMap<>();
    private static final Map<String, Vec3i> PENDING = new ConcurrentHashMap<>();

    /** Names measured and found to have no template — see {@link #measure}. */
    private static final Set<String> UNMEASURABLE = ConcurrentHashMap.newKeySet();

    private PrefabSizes() {}

    /** Record the size of a template that was just loaded. */
    public static void observe(String name, Vec3i size) {
        if (name == null || size == null) return;
        SIZES.put(name, size);
        UNMEASURABLE.remove(name);
    }

    /** Record a size that has just been written to disk. The template is now the authority. */
    public static void settle(String name, Vec3i size) {
        if (name == null || size == null) return;
        SIZES.put(name, size);
        PENDING.remove(name);
        UNMEASURABLE.remove(name);
    }

    /** The size {@code name}'s plot and stamp should use. */
    public static Vec3i sizeOf(String name) {
        if (name == null) return TrackKind.PREFAB_DEFAULT_SIZE;
        Vec3i pending = PENDING.get(name);
        if (pending != null) return pending;
        Vec3i known = SIZES.get(name);
        if (known == null) known = measure(name);
        return known != null ? known : TrackKind.PREFAB_DEFAULT_SIZE;
    }

    /** True when {@code name} has a saved template (or a load has been observed). */
    public static boolean hasTemplate(String name) {
        if (name == null) return false;
        if (SIZES.containsKey(name)) return true;
        return measure(name) != null;
    }

    /**
     * The size in {@code name}'s template file, remembered for next time; null when there is no
     * template to measure. Misses are remembered too ({@link #UNMEASURABLE}) so a per-tick layout
     * walk never repeats a filesystem miss.
     */
    @Nullable
    private static Vec3i measure(String name) {
        if (UNMEASURABLE.contains(name)) return null;
        Vec3i size = PortalRoomTemplateSize.read(TrackKind.PREFAB, name);
        if (size == null) {
            UNMEASURABLE.add(name);
            return null;
        }
        SIZES.put(name, size);
        return size;
    }

    /** Editor override — the plot restamps at this size until the next save bakes it in. */
    public static void pending(String name, Vec3i size) {
        if (name == null || size == null) return;
        PENDING.put(name, size);
    }

    /** Drop the editor override, leaving the size the template last reported. */
    public static void revert(String name) {
        if (name == null) return;
        PENDING.remove(name);
    }

    /** Drop everything known about {@code name} — it has been deleted. */
    public static void forget(String name) {
        if (name == null) return;
        SIZES.remove(name);
        PENDING.remove(name);
        UNMEASURABLE.remove(name);
    }

    /** Drop every cached size. Called when the variant registry reloads on server start. */
    public static void clear() {
        SIZES.clear();
        PENDING.clear();
        UNMEASURABLE.clear();
    }
}
