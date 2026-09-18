package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.videos.PlatformToggleButton;
import games.brennan.dungeontrain.client.videos.VideoCatalog;
import games.brennan.dungeontrain.client.videos.VideoList;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BooleanSupplier;

/**
 * The title-screen icon that opens the Videos page; sits in the icon column between Credits and
 * Discord (see {@code TitleScreenCreditsButton}).
 *
 * <p>A red rounded tile with a white play triangle — the shape every platform uses for "video",
 * drawn programmatically like {@link DiscordIconButton} so no texture ships. One face for every
 * client: the Bilibili mark ({@link BilibiliIconButton}) appears only on the Videos page itself,
 * as the channel link at the bottom, rather than standing in for this icon on a Chinese-language
 * client as it used to.</p>
 *
 * <p>A pulsing green dot rides the top-right corner while a streamer is live
 * ({@link VideoCatalog#anyLive()}) — the same dot the page draws on the live row — so the menu
 * says "someone is on right now" without the page being opened.</p>
 *
 * <h3>The share tab</h3>
 * <p>While {@code showShareTab} says so — on the title screen, that is "OBS is running"
 * ({@code StreamingSoftwareDetector}) — the tile grows a tag out of its <b>right</b> edge reading
 * "Streaming? Share link!", eased out over {@link #EXTEND_MS} and clipped as it goes so the text
 * slides out from behind the icon. The button's own width follows the animation, so the whole tag
 * is hover-tinted and clickable; the click is the same as ever (the Videos page, which carries the
 * submit button). The icon never moves and nothing is drawn to its left — that side is the vanilla
 * button grid. Where the full line would run off the screen (an auto-scaled 427-px GUI leaves under
 * 90 px beside the column) the tag says the shorter "Share stream!" instead; only if even that has no
 * room does it stay folded, and the icon is unchanged either way. A {@link ShareTabCloseButton} rides
 * above the tab's far end; clicking it folds the tab for the rest of the session.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VideosIconButton extends Button {

    private static final int RED       = 0xFFE53935;
    private static final int RED_HOVER = 0xFFEF5350;
    private static final int MARK      = 0xFFFFFFFF;
    private static final float LIVE_DOT_SCALE = 0.3F;
    private static final int MIN_LIVE_DOT = 4;

    private static final Component SHARE_TAB_TEXT = Component.translatable("gui.dungeontrain.videos.share_tab");
    /** The wording the tab falls back to when the full line has no room beside the icon. */
    private static final Component SHARE_TAB_SHORT = Component.translatable("gui.dungeontrain.videos.share_tab.short");
    /** Horizontal padding inside the tag, either side of the text. */
    private static final int TAB_PAD = 4;
    /** Breathing room the tag must leave to the screen's right edge, else it stays folded. */
    private static final int TAB_SCREEN_MARGIN = 4;
    /** Full extend (or retract) takes this long. Wall-clock, like the Discord pulse. */
    private static final long EXTEND_MS = 350L;

    /** Set when the player clicks the tab's close mark; the tab stays folded for the session. */
    private static boolean shareTabDismissed = false;

    private final BooleanSupplier showShareTab;
    /** The tile's side — {@code getWidth()} grows past this while the tag is out. */
    private final int iconSize;
    /** Extension progress 0..1 at the last frame, and when that frame was — the lerp state. */
    private float extend = 0.0F;
    private long lastFrameMs = -1L;

    public VideosIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        this(x, y, size, narration, onPress, () -> false);
    }

    public VideosIconButton(int x, int y, int size, Component narration, OnPress onPress,
                            BooleanSupplier showShareTab) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
        this.iconSize = size;
        this.showShareTab = showShareTab == null ? () -> false : showShareTab;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // The width follows the lerp so the tag is clickable as it grows. AbstractWidget.render is
        // final and has already judged hover against last frame's width, so re-judge it here — a
        // one-frame-stale edge on a 350ms slide is otherwise invisible, but a stale edge on the
        // fully-out tag would leave its last few pixels un-tinted.
        setWidth(iconSize + Math.round(advance() * tabWidth()));
        this.isHovered = mouseX >= getX() && mouseY >= getY()
                && mouseX < getX() + getWidth() && mouseY < getY() + getHeight();
        int x = getX();
        int y = getY();
        int s = iconSize;
        int inset = Math.max(1, Math.round(s * 0.08F));
        int body = isHoveredOrFocused() ? RED_HOVER : RED;

        renderShareTab(g, x, y, s, inset, body);

        // Red tile, corners nipped so it reads as the rounded app icon rather than a square.
        g.fill(x + inset, y, x + s - inset, y + s, body);
        g.fill(x, y + inset, x + s, y + s - inset, body);
        PlatformToggleButton.drawPlay(g, x, y, s, MARK);
        if (VideoCatalog.anyLive()) {
            // Badge: overhangs the corner by a pixel so it reads as a notification, not artwork.
            int dot = Math.max(MIN_LIVE_DOT, Math.round(s * LIVE_DOT_SCALE));
            VideoList.drawLiveDot(g, x + s - dot + 1, y - 1, dot);
        }
    }

    /**
     * The tag, tile-height, drawn under the tile so the nipped corner sits over its root. The text is
     * scissored to the tag's current extent so it emerges from behind the icon rather than fading.
     */
    private void renderShareTab(GuiGraphics g, int x, int y, int s, int inset, int body) {
        int out = getWidth() - s;
        if (out <= 0) return;
        int left = x + s - inset;
        int right = x + getWidth();
        // Full tile height — the tag reads as the tile itself stretching, not a tag hung off it.
        int top = y;
        int bottom = y + s;
        // Right end nipped by the same inset as the tile's corners, so the two read as one shape.
        int nip = Math.min(inset, out);
        g.fill(left, top, right - nip, bottom, body);
        g.fill(right - nip, top + inset, right, bottom - inset, body);

        Component text = tabText();
        if (text == null) return; // window shrank mid-retract: the bar folds away wordless
        Font font = Minecraft.getInstance().font;
        int textX = x + s + TAB_PAD;
        int textY = y + (s - font.lineHeight) / 2 + 1;
        g.enableScissor(x + s, top, right, bottom);
        g.drawString(font, text, textX, textY, MARK, true);
        g.disableScissor();
    }

    /**
     * The wording that fits beside the icon: the full line where the screen allows, the short one
     * where it doesn't (a 427-px-wide auto-scaled window leaves under 90 px), null when not even that
     * fits. Measured per frame — the answer changes with the window, and the font is already hot.
     */
    private Component tabText() {
        Minecraft mc = Minecraft.getInstance();
        int room = mc.getWindow().getGuiScaledWidth() - TAB_SCREEN_MARGIN - getX() - iconSize;
        if (mc.font.width(SHARE_TAB_TEXT) + 2 * TAB_PAD <= room) return SHARE_TAB_TEXT;
        if (mc.font.width(SHARE_TAB_SHORT) + 2 * TAB_PAD <= room) return SHARE_TAB_SHORT;
        return null;
    }

    /** Pixels the tag adds to the width when fully out; zero when no wording has room. */
    private int tabWidth() {
        Component text = tabText();
        return text == null ? 0 : Minecraft.getInstance().font.width(text) + 2 * TAB_PAD;
    }

    /** Fold the tab away for the rest of the session — the close mark's click. */
    public static void dismissShareTab() {
        shareTabDismissed = true;
    }

    /** Whether the tab is fully out — when the close mark above it is shown and clickable. */
    public boolean isShareTabOut() {
        return extend >= 1.0F && getWidth() > iconSize;
    }

    /** Steps the lerp toward the supplier's answer by the wall-clock since the last frame; returns eased 0..1. */
    private float advance() {
        long now = Util.getMillis();
        // First frame steps nothing, so a fresh button (every title-screen visit) always slides out.
        float step = lastFrameMs < 0 ? 0.0F : (now - lastFrameMs) / (float) EXTEND_MS;
        lastFrameMs = now;
        float target = showShareTab.getAsBoolean() && !shareTabDismissed ? 1.0F : 0.0F;
        extend = target > extend ? Math.min(target, extend + step) : Math.max(target, extend - step);
        return extend * extend * (3.0F - 2.0F * extend);
    }
}
