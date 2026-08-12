package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.Vec3i;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How big each named portal room is, without needing a {@code ServerLevel} to ask.
 *
 * <p>A room's size lives in its template and nowhere else — that is what "free above a floor" means.
 * But the editor's plot layout has to size a room's plot from
 * {@code TrackSidePlots.locate(pos, dims)}, which resolves a block position to a plot and has no
 * level to load templates through. So every load records the size here, and the layout reads it
 * back.</p>
 *
 * <p>A name with no entry is the built-in room's size — either nothing has been authored for it yet
 * or nothing has looked at it yet, and the built-in room is what would be stamped in both cases.</p>
 *
 * <p>{@link #pending} is the editor's override: a size stepper restamps the plot at a new size
 * before there is a template to read it from. It survives {@link #observe} on purpose — stamping the
 * plot loads the template on its way to reading the size, so clearing it there would undo the resize
 * before it ever took effect. Only {@link #settle}, a save, spends it.</p>
 */
public final class PortalRoomSizes {

    private static final Map<String, Vec3i> SIZES = new ConcurrentHashMap<>();
    private static final Map<String, Vec3i> PENDING = new ConcurrentHashMap<>();

    private PortalRoomSizes() {}

    /** Record the size of a template that was just loaded. */
    public static void observe(String name, Vec3i size) {
        if (name == null || size == null) return;
        SIZES.put(name, size);
    }

    /** Record a size that has just been written to disk. The template is now the authority. */
    public static void settle(String name, Vec3i size) {
        if (name == null || size == null) return;
        SIZES.put(name, size);
        PENDING.remove(name);
    }

    /** The size {@code name}'s plot and stamp should use, clamped to what this world allows. */
    public static Vec3i sizeOf(String name, CarriageDims dims) {
        if (name == null) return PortalRoomLayout.builtInSize(dims);
        Vec3i pending = PENDING.get(name);
        if (pending != null) return PortalRoomLayout.clampSize(dims, pending);
        Vec3i known = SIZES.get(name);
        return known != null
            ? PortalRoomLayout.clampSize(dims, known)
            : PortalRoomLayout.builtInSize(dims);
    }

    /** Editor override — the plot restamps at this size until the next save bakes it in. */
    public static void pending(String name, Vec3i size) {
        if (name == null || size == null) return;
        PENDING.put(name, size);
    }

    /**
     * Drop the editor override, leaving the size the template last reported.
     *
     * <p>What the editor's Reset needs. {@link #settle} also clears the override, but only because a
     * save has just made a new size authoritative — calling it here would bake the abandoned resize
     * in as the known size, which is the opposite of a reset.</p>
     */
    public static void revert(String name) {
        if (name == null) return;
        PENDING.remove(name);
    }

    /** Drop everything known about {@code name} — it has been deleted. */
    public static void forget(String name) {
        if (name == null) return;
        SIZES.remove(name);
        PENDING.remove(name);
    }

    /** Drop every cached size. Called when the variant registry reloads on server start. */
    public static void clear() {
        SIZES.clear();
        PENDING.clear();
    }
}
