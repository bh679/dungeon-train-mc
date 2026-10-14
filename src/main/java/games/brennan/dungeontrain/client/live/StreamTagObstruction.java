package games.brennan.dungeontrain.client.live;

/**
 * Where {@link StreamNameTag}'s pill sits so another mod's top-middle HUD box doesn't cover it —
 * today Jade's look-at tooltip, reported each frame it is drawn by
 * {@code compat.jade.LiveEyeJadeClient}. Nothing here touches Jade, so the class loads without it.
 *
 * <p>The pill drops under the box at once, but only climbs back once the box has been gone (or
 * smaller) for {@link #LINGER_MS}, and then eases up — glancing from block to air to block doesn't
 * bounce it for viewers. A box off to the side (Jade moved to a corner) leaves the pill where it is.</p>
 *
 * <p>Render thread only. All positions are GUI pixels.</p>
 */
public final class StreamTagObstruction {

    /** GUI pixels between the box's bottom and the pill's top. */
    static final int GAP = 3;
    /** A box last drawn longer ago than this is gone. */
    static final long FRESH_MS = 250;
    /** How long the pill stays down after the box goes before it climbs back. */
    static final long LINGER_MS = 1500;
    /** Climb speed once lingering is over, GUI pixels per millisecond. */
    static final float RISE_PX_PER_MS = 0.15f;
    /** Longest frame step counted toward the climb, so a stall doesn't teleport the pill. */
    static final long MAX_STEP_MS = 100;

    /** The last box drawn: left/right/bottom edges and when. */
    record Box(int left, int right, int bottom, long atMs) {}

    /** Where the pill is: its top, until when it holds there, and when this was worked out. */
    record Placement(float top, long holdUntilMs, long atMs) {}

    private static Box box;
    private static Placement placement;

    private StreamTagObstruction() {}

    /** A top-middle box was drawn this frame, spanning {@code left..right} and down to {@code bottom}. */
    public static void report(int left, int right, int bottom, long nowMs) {
        box = new Box(left, right, bottom, nowMs);
    }

    /** The pill's top this frame, for a pill spanning {@code pillLeft..pillRight} that would sit at {@code base}. */
    static float top(float pillLeft, float pillRight, int base, long nowMs) {
        placement = next(placement, target(box, pillLeft, pillRight, base, nowMs), nowMs);
        return placement.top();
    }

    /** Where the pill wants to be: under a fresh box it overlaps, else at {@code base}. */
    static float target(Box box, float pillLeft, float pillRight, int base, long nowMs) {
        if (box == null || nowMs - box.atMs() > FRESH_MS || nowMs < box.atMs()) return base;
        boolean overlaps = box.left() < pillRight && box.right() > pillLeft;
        return overlaps ? Math.max(base, box.bottom() + GAP) : base;
    }

    /** Down at once; up only after {@link #LINGER_MS}, then at {@link #RISE_PX_PER_MS}. */
    static Placement next(Placement prev, float target, long nowMs) {
        if (prev == null || target >= prev.top()) return new Placement(target, nowMs + LINGER_MS, nowMs);
        if (nowMs < prev.holdUntilMs()) return new Placement(prev.top(), prev.holdUntilMs(), nowMs);
        long step = Math.min(MAX_STEP_MS, Math.max(0, nowMs - prev.atMs()));
        float top = Math.max(target, prev.top() - step * RISE_PX_PER_MS);
        return new Placement(top, prev.holdUntilMs(), nowMs);
    }
}
