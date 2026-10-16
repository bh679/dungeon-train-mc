package games.brennan.dungeontrain.client.live;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/**
 * Where {@link StreamNameTag}'s pill sits on screen while this player is live, so another mod's
 * top-middle HUD box can move out from under it — today Jade's look-at tooltip, shifted down by
 * {@code compat.jade.LiveEyeJadeClient}. Nothing here touches Jade, so the class loads without it.
 *
 * <p>The pill owns the top-middle: it never moves, the box does. A box off to the side (Jade moved
 * to a corner) is left where it is. Render thread only; all positions are GUI pixels.</p>
 */
public final class LivePillSlot {

    /** GUI pixels between the pill's bottom and the box's top. */
    static final int GAP = 3;

    /** The pill's footprint: left/right edges and bottom. */
    public record Bounds(float left, float right, int bottom) {}

    private LivePillSlot() {}

    /** The pill's footprint on a screen {@code guiWidth} GUI pixels wide, or null when no pill is shown. */
    @Nullable
    public static Bounds bounds(int guiWidth) {
        if (!LiveStreamController.get().streaming()) return null;
        float width = StreamNameTag.pillGuiWidth(Minecraft.getInstance().font);
        if (width <= 0f) return null;
        return bounds(guiWidth, width);
    }

    /** A {@code pillWidth}-wide pill centred on a {@code guiWidth}-wide screen. */
    static Bounds bounds(int guiWidth, float pillWidth) {
        float left = (guiWidth - pillWidth) / 2f;
        return new Bounds(left, left + pillWidth, StreamNameTag.TOP_OFFSET + StreamNameTag.pillGuiHeight());
    }

    /**
     * How far down a box spanning {@code boxLeft..boxRight} with its top at {@code boxTop} must move
     * to clear {@code pill}: none when there is no pill, the box is off to the side or already below.
     */
    public static int shift(int boxLeft, int boxRight, int boxTop, @Nullable Bounds pill) {
        if (pill == null) return 0;
        boolean overlaps = boxLeft < pill.right() && boxRight > pill.left();
        return overlaps ? Math.max(0, pill.bottom() + GAP - boxTop) : 0;
    }
}
