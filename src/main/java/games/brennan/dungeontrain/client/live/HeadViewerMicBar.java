package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.Status;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

/**
 * The streamer's mic level on a viewer's pinned box ({@link LiveHeadViewer}): a bar one GUI pixel
 * wide up the right edge of the pane, rising from the bottom with the voice ({@link LiveMicMeter}),
 * the whole height of the pane at full level.
 *
 * <p>Shown only while the relay says the stream's mic is on ({@link Status#mic()}), the pane is
 * playing that stream live (not the dark channel's replay, not a stream the box is still switching
 * from) with sound in, and the viewer has not muted the stream, its streamer or TV stream sound
 * ({@link LiveViewerMutes#audible}). The streamer's own preview carries no bar: their overlay has
 * the mic icon.</p>
 */
final class HeadViewerMicBar {

    /** One GUI pixel wide. */
    static final int WIDTH = 1;
    /** The green the streamer's mic icon fills with as they speak. */
    static final int COLOR = 0xFF54FF54;

    private HeadViewerMicBar() {}

    /** The bar's height for {@code level} (0 to 1) on a pane {@code paneHeight} tall: full at 1, none at 0. Pure. */
    static int barHeight(float level, int paneHeight) {
        return Math.round(LiveMicMeter.clamp(level) * paneHeight);
    }

    /**
     * Whether the pane for {@code second} gets a bar: its stream's status says the mic is on, and the
     * session on the pane is that stream, live, with sound arriving. Pure. The mute check is separate
     * ({@link #draw}).
     */
    static boolean shows(@Nullable Status polled, boolean second, @Nullable LiveHlsSession session) {
        Status s = polled == null ? null : second ? polled.second() : polled;
        if (s == null || !s.live() || s.mic() == null || !s.mic().on() || s.playlistUrl() == null) return false;
        return session != null && !session.isReplay() && session.hasSound() && s.playlistUrl().equals(session.playlistUrl());
    }

    /** Render thread: the bar up the right edge of pane {@code b}, if the stream on {@code source} earns one. */
    static void draw(GuiGraphics g, LiveFeedSource source, LiveHeadViewer.Box b, boolean second) {
        Status polled = LiveStatusPoller.current();
        LiveHlsSession session = source.shownSession();
        if (!shows(polled, second, session)) return;
        Status s = second ? polled.second() : polled;
        if (!LiveViewerMutes.audible(session.playlistUrl(), s.streamer())) return;
        int h = barHeight(session.micLevel(), b.h());
        if (h <= 0) return;
        int x1 = b.x() + b.w();
        int y1 = b.y() + b.h();
        g.fill(x1 - WIDTH, y1 - h, x1, y1, COLOR);
    }
}
