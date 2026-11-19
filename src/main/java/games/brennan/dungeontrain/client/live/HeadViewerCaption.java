package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Status;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * "● <b>Name</b> is live" on a dark strip right under each pane of a viewer's pinned box
 * ({@link LiveHeadViewer}), so a TV on the head says whose stream it is showing. The strip's room is
 * kept while the setting is on ({@link LiveFeedClientConfig#headViewerNames()}), whether or not anyone
 * is live, so the second pane and the takeover button never jump as streams come and go. The
 * streamer's own box carries no caption: they have their own name pill ({@link StreamNameTag}).
 */
final class HeadViewerCaption {

    /** GUI pixels the strip takes under a pane's bottom border: one line of text and a pixel either side. */
    static final int HEIGHT = 11;
    static final int PAD_X = 3;
    static final int DOT = 3;
    static final int DOT_GAP = 3;
    private static final int BACKING = 0xC8000000;
    private static final int DOT_COLOR = 0xFF000000 | StreamNameTag.DOT_RGB;

    private HeadViewerCaption() {}

    /** The room under each pane: {@link #HEIGHT} with the setting on, none with it off. */
    static int height() {
        return LiveFeedClientConfig.headViewerNames() ? HEIGHT : 0;
    }

    /** {@code b} grown down over its caption, for whatever hangs under the box (the takeover button). */
    static LiveHeadViewer.Box withCaption(LiveHeadViewer.Box b) {
        int h = height();
        return h == 0 ? b : new LiveHeadViewer.Box(b.x(), b.y(), b.w(), b.h() + h);
    }

    /**
     * Who is live on the pane's stream ({@code second}: the Two Streams second channel), or null when it
     * is dark (the replay plays then) or the relay sent no name. Pure.
     */
    @Nullable
    static String nameFor(@Nullable Status status, boolean second) {
        Status s = status == null ? null : second ? status.second() : status;
        if (s == null || !s.live()) return null;
        String name = s.streamer();
        return name == null || name.isBlank() ? null : name.strip();
    }

    /** The strip under pane {@code b}, when the setting is on and someone is live on it. */
    static void draw(GuiGraphics g, LiveHeadViewer.Box b, boolean second) {
        if (height() == 0) return;
        String name = nameFor(LiveStatusPoller.current(), second);
        if (name == null) return;
        Font font = Minecraft.getInstance().font;
        Component text = StreamNameTag.label(name, false);
        int x0 = b.x() - LiveHeadViewer.BORDER;
        int x1 = b.x() + b.w() + LiveHeadViewer.BORDER;
        int top = b.y() + b.h() + LiveHeadViewer.BORDER;
        g.fill(x0, top, x1, top + HEIGHT, BACKING);
        // Shrunk to fit a narrow box, so a long name is never cut off.
        int room = (x1 - x0) - 2 * PAD_X - DOT - DOT_GAP;
        float scale = Math.max(0.1f, Math.min(1f, room / (float) Math.max(1, font.width(text))));
        float groupW = DOT + DOT_GAP + font.width(text) * scale;
        float left = x0 + ((x1 - x0) - groupW) / 2f;
        int cy = top + HEIGHT / 2;
        int dotX = Math.round(left);
        g.fill(dotX, cy - DOT / 2, dotX + DOT, cy - DOT / 2 + DOT, DOT_COLOR);
        g.pose().pushPose();
        g.pose().translate(left + DOT + DOT_GAP, cy - font.lineHeight * scale / 2f + 0.5f, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, text, 0, 0, 0xFFFFFFFF, true);
        g.pose().popPose();
    }
}
