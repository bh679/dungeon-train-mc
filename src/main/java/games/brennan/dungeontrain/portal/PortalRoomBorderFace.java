package games.brennan.dungeontrain.portal;

import java.util.ArrayList;
import java.util.List;

/**
 * One wall of a World Border room's border, with a corridor's mouth left open in it.
 *
 * <p>The west and east faces are where the corridors meet the room, and a border drawn straight
 * across them would stand in the doorway. So the face is cut into up to four rectangles around the
 * corridor's cross-section: the full-width strips below and above it, and the two pieces either side
 * of it. The stripes keep one phase across the cut because their UVs come from world position, not
 * from the piece, so what is left reads as the same wall with a hole in it.</p>
 *
 * <p>Pure, so the cut is tested without a client — see {@code PortalRoomBorderFaceTest}.</p>
 */
public final class PortalRoomBorderFace {

    /** A rectangle of wall: {@code along} runs the face's horizontal axis, max edges exclusive. */
    public record Piece(int along0, int along1, int bottom, int top) {}

    private PortalRoomBorderFace() {}

    /**
     * The face {@code [along0, along1) x [bottom, top)} less the hole
     * {@code [holeAlong0, holeAlong1) x [holeBottom, holeTop)}, as non-overlapping pieces. The hole is
     * clamped to the face first; an empty hole (or one entirely off the face) leaves the face whole.
     */
    public static List<Piece> split(int along0, int along1, int bottom, int top,
                                    int holeAlong0, int holeAlong1, int holeBottom, int holeTop) {
        List<Piece> pieces = new ArrayList<>(4);
        if (along1 <= along0 || top <= bottom) return pieces;
        int a0 = Math.max(along0, holeAlong0);
        int a1 = Math.min(along1, holeAlong1);
        int b = Math.max(bottom, holeBottom);
        int t = Math.min(top, holeTop);
        if (a1 <= a0 || t <= b) {
            pieces.add(new Piece(along0, along1, bottom, top));
            return pieces;
        }
        if (b > bottom) pieces.add(new Piece(along0, along1, bottom, b));
        if (t < top) pieces.add(new Piece(along0, along1, t, top));
        if (a0 > along0) pieces.add(new Piece(along0, a0, b, t));
        if (a1 < along1) pieces.add(new Piece(a1, along1, b, t));
        return pieces;
    }
}
