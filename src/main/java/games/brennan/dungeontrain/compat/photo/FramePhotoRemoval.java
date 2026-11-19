package games.brennan.dungeontrain.compat.photo;

/**
 * "A photo is coming out of a frame right now." Entered and left around the drop when a frame gives up
 * its photo: {@code PhotographFrameBlock} (a left-click taking the photo out, or breaking the frame) and
 * {@code mixin.HangingFrameDropMixin} (Exposure's frame entity, vanilla item frames). The
 * {@code EntityJoinLevelEvent} that fires inside it lets a disposable-camera photograph land intact, so the
 * player who took it down can pick it up; it burns as normal once viewed or dropped again. Server thread
 * only, synchronous, nestable.
 */
public final class FramePhotoRemoval {

    private static int depth;

    private FramePhotoRemoval() {}

    public static void begin() {
        depth++;
    }

    public static void end() {
        if (depth > 0) depth--;
    }

    /** True while a frame's photo drop is on the stack. */
    public static boolean inProgress() {
        return depth > 0;
    }
}
