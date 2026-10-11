package games.brennan.dungeontrain.client.live;

/**
 * How wide the shared feed decode should be, given who is looking at it.
 *
 * <p>A viewer decodes at the configured viewer width, as every TV does. A streamer whose only use
 * for the feed is their own pinned box (no TV block has asked for it lately) decodes at the box's
 * real pixel size instead — a thumbnail, on the client already paying for the encoder.</p>
 */
final class LiveDecodeWidth {

    /** Never decode narrower than this, however small the box. */
    static final int MIN = 160;

    private LiveDecodeWidth() {}

    /**
     * @param streaming    this client is broadcasting
     * @param tvWants      a TV block rendered the feed recently
     * @param boxGuiWidth  the streamer's pinned box width, in GUI pixels
     * @param guiScale     window pixels per GUI pixel
     * @param viewerWidth  the configured viewer decode width — never exceeded
     * @return an even decode width
     */
    static int forConsumer(boolean streaming, boolean tvWants, int boxGuiWidth, double guiScale, int viewerWidth) {
        if (!streaming || tvWants) return viewerWidth;
        int w = (int) Math.round(boxGuiWidth * guiScale);
        if (w % 2 != 0) w++;
        w = Math.max(MIN, w);
        return Math.min(viewerWidth, w);
    }
}
