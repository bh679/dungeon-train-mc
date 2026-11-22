package games.brennan.dungeontrain.portal;

/**
 * How far up and down a World Border room's border is drawn — the vertical half of
 * {@code PortalRoomBorderRenderer}, kept free of Minecraft types so it unit-tests without a
 * bootstrap, like {@link PortalSealPlane}.
 *
 * <p>Vanilla's world border runs the full height of the world, and this one is meant to read the
 * same way: a wall with no top or bottom. But a room stands in sealed twin space, and the whole point
 * of the seal is that the two sides of it never see each other. So the border runs out to the seal
 * planes the camera is held between and stops there — exactly where the barrier already cuts the
 * world away, so it never shows above the bedrock ceiling from inside, and the train never sees
 * it.</p>
 *
 * <p>An unbounded side of the cut runs to the world's build limit. A cut that hides nothing at all —
 * seal culling switched off, or a camera the client cannot place in twin space — falls back to the
 * twin's own band if one is known, and to the room's own height if not, so the border can never be
 * drawn up into the world.</p>
 */
public final class PortalRoomBorderSpan {

    private PortalRoomBorderSpan() {}

    /**
     * The border's {@code [bottom, top]} in world Y, as block-face coordinates.
     *
     * @param cut      the seal cut in force this frame
     * @param twinCut  the cut for the room's own twin band, for when {@code cut} hides nothing;
     *                 {@code null} when no band is known
     * @param worldMin the level's lowest build row
     * @param worldMax one past the level's highest build row
     * @param roomMin  the room's floor row
     * @param roomMax  one past the room's top row
     * @return two values, bottom then top; never inverted
     */
    public static int[] of(PortalSealPlane.Cut cut, PortalSealPlane.Cut twinCut,
                           int worldMin, int worldMax, int roomMin, int roomMax) {
        PortalSealPlane.Cut use = cut != null && cut.sealed() ? cut
            : twinCut != null && twinCut.sealed() ? twinCut : null;
        if (use == null) return new int[] {roomMin, roomMax};
        int bottom = use.floorY() == Integer.MIN_VALUE ? worldMin : Math.max(worldMin, use.floorY());
        int top = use.roofY() == Integer.MAX_VALUE ? worldMax : Math.min(worldMax, use.roofY());
        if (top <= bottom) return new int[] {roomMin, roomMax};
        return new int[] {bottom, top};
    }
}
