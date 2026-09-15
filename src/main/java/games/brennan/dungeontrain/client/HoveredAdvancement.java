package games.brennan.dungeontrain.client;

import net.minecraft.Util;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;

/**
 * The advancement whose tooltip was drawn most recently, and when.
 *
 * <p>Both advancements screens (vanilla and Better Advancements) call their widget's
 * {@code drawHover} for exactly the one tile under the mouse each frame, so recording the id there
 * gives a click handler the hovered advancement without reaching into either tab's private
 * scroll/widget geometry. A click counts as "on this tile" only while the record is fresher than
 * {@link #FRESH_MILLIS} — a frame or two — so a stale hover from before the mouse left can't be
 * clicked.</p>
 */
public final class HoveredAdvancement {

    /** How long a recorded hover stays valid for a click, in ms — comfortably more than a frame. */
    private static final long FRESH_MILLIS = 100L;

    private static ResourceLocation id;
    private static boolean earned;
    private static long at;

    private HoveredAdvancement() {}

    /**
     * Note the tile whose tooltip is being drawn. The widget already holds the progress, and the
     * client's progress map is private, so the earned state rides along here for the click rule.
     */
    public static void record(ResourceLocation hovered, AdvancementProgress progress) {
        id = hovered;
        earned = progress != null && progress.isDone();
        at = Util.getMillis();
    }

    /** The hovered advancement if one was drawn within the last {@link #FRESH_MILLIS}, else null. */
    public static ResourceLocation current() {
        if (id == null || Util.getMillis() - at > FRESH_MILLIS) return null;
        return id;
    }

    /** Whether the advancement {@link #current()} refers to was already earned when it was drawn. */
    public static boolean currentIsEarned() {
        return earned;
    }
}
