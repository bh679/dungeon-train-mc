package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.menu.ColorTintedButton;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * "Ways to Help" hub, opened from the title-screen support button (see
 * {@link TitleScreenSupportButton}). A full-screen panel organised into the
 * three ways a player can support Dungeon Train:
 *
 * <ol>
 *   <li><b>Financial Support</b> — Patreon (green) + the Kinetic Hosting
 *       affiliate (blue), the same referral shipped in the modpack's
 *       {@code khi.toml}. Rendered as colour-coded buttons.</li>
 *   <li><b>Share the Mod</b> — text only, with an inline clickable "Discord"
 *       link to post videos in.</li>
 *   <li><b>Feedback &amp; Testing</b> — text only, with inline clickable
 *       "Discord" links to share ideas and get involved.</li>
 * </ol>
 *
 * <p>Both the coloured buttons and the inline "Discord" links open their URL
 * through vanilla's {@link ConfirmLinkScreen} — the standard "open external
 * link?" confirmation. Confirming or cancelling returns to <em>this</em> hub
 * (not the title screen) so the player can follow several links in one visit.
 * Inline links are hit-tested in {@link #mouseClicked} against the wrapped
 * description lines.</p>
 *
 * <p>Section headers and descriptions are drawn in {@link #render} over a
 * translucent contrast panel painted in {@link #renderBackground}; the Financial
 * buttons are real widgets added in {@link #init} so they draw on top of that
 * panel.</p>
 */
public final class SupportScreen extends Screen {

    private static final String PATREON_URL   = "https://www.patreon.com/brennanhatton";
    private static final String AFFILIATE_URL = "https://billing.kinetichosting.com/aff.php?aff=1461";
    private static final String DISCORD_URL   = "https://discord.gg/jdKAwb6rbW";

    private static final int MAX_COL_W  = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int BUTTON_H   = 20;
    private static final int BUTTON_GAP = 6;
    private static final int SECTION_GAP = 8;
    private static final int HEADER_GAP = 3;
    private static final int DESC_GAP   = 4;
    private static final int TOP        = 16;
    private static final int PANEL_PAD  = 10;

    private static final int COLOUR_PANEL  = 0xC0101010;
    private static final int COLOUR_HEADER = 0xFFFFFFFF;
    private static final int COLOUR_DESC   = 0xFFCACACA;
    /** Blue used for the inline "Discord" links (RGB, no alpha). */
    private static final int COLOUR_LINK   = 0x5B9BFF;

    /** Sprite tints (multiplied over the grey button sprite). */
    private static final float[] TINT_GREEN = {0.30F, 0.80F, 0.35F}; // Patreon
    private static final float[] TINT_BLUE  = {0.35F, 0.55F, 1.00F}; // Kinetic Hosting

    private final Screen parent;

    // Computed in init(), consumed in render().
    private int colX;
    private int colW;
    private int panelTop;
    private int panelBottom;
    private int subtitleY;
    private List<FormattedCharSequence> subtitleLines = List.of();
    private final List<TextBlock> textBlocks = new ArrayList<>();

    /** A section's non-widget text: header line + wrapped description, with their Y positions. */
    private record TextBlock(Component header, int headerY, List<FormattedCharSequence> descLines, int descY) {}

    /** One link button in a section: label key + target URL + optional sprite tint (null = default grey). */
    private record LinkButton(String labelKey, String url, float[] tint) {}

    public SupportScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.support.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        textBlocks.clear();
        colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        colX = (this.width - colW) / 2;
        int lh = this.font.lineHeight;

        int y = TOP;
        panelTop = y - PANEL_PAD;

        // Title is drawn centred at TOP in render(); reserve its line here.
        y += lh + 6;

        subtitleLines = this.font.split(Component.translatable("gui.dungeontrain.support.subtitle"), colW);
        subtitleY = y;
        y += subtitleLines.size() * lh + 8;

        y = addSection(y, lh,
                Component.translatable("gui.dungeontrain.support.financial.header"),
                Component.translatable("gui.dungeontrain.support.financial.desc"),
                new LinkButton("gui.dungeontrain.support.financial.patreon", PATREON_URL, TINT_GREEN),
                new LinkButton("gui.dungeontrain.support.financial.affiliate", AFFILIATE_URL, TINT_BLUE));

        // Share / Feedback — text only, with inline clickable "Discord" links
        // spliced into the copy (see discordLink()).
        y = addSection(y, lh,
                Component.translatable("gui.dungeontrain.support.share.header"),
                Component.translatable("gui.dungeontrain.support.share.desc", discordLink()));

        y = addSection(y, lh,
                Component.translatable("gui.dungeontrain.support.feedback.header"),
                Component.translatable("gui.dungeontrain.support.feedback.desc", discordLink(), discordLink()));

        panelBottom = y + (PANEL_PAD - SECTION_GAP);

        // Done flows just below the content panel so it can never collide with a
        // section's content, regardless of screen height / GUI scale.
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, panelBottom + 8, 200, 20)
                .build());
    }

    /**
     * Lay out one section (header text + wrapped description + an optional row of
     * link buttons), registering the text for {@link #render} and the buttons as
     * live widgets. Returns the Y just below the section.
     */
    private int addSection(int y, int lh, Component header, Component desc, LinkButton... links) {
        int headerY = y;
        y += lh + HEADER_GAP;

        List<FormattedCharSequence> descLines = this.font.split(desc, colW);
        int descY = y;
        y += descLines.size() * lh + DESC_GAP;

        textBlocks.add(new TextBlock(header, headerY, descLines, descY));

        int n = links.length;
        if (n == 0) {
            // Text-only section — no button row to reserve.
            return y + SECTION_GAP;
        }
        int each = (colW - (n - 1) * BUTTON_GAP) / n;
        for (int i = 0; i < n; i++) {
            int bx = colX + i * (each + BUTTON_GAP);
            addRenderableWidget(makeLinkButton(bx, y, each, BUTTON_H, links[i]));
        }
        return y + BUTTON_H + SECTION_GAP;
    }

    /** Build a link button, tinted to {@link LinkButton#tint()} when set, else the default grey. */
    private Button makeLinkButton(int x, int y, int w, int h, LinkButton lb) {
        Component label = Component.translatable(lb.labelKey());
        Button.OnPress onPress = b -> openLink(lb.url());
        float[] t = lb.tint();
        if (t == null) {
            return new DarkTintedButton(x, y, w, h, label, onPress);
        }
        return new ColorTintedButton(x, y, w, h, label, t[0], t[1], t[2], onPress);
    }

    /** A clickable, blue, underlined "Discord" word for splicing into description copy. */
    private Component discordLink() {
        return Component.literal("Discord").withStyle(s -> s
                .withColor(COLOUR_LINK)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, DISCORD_URL)));
    }

    private void openLink(String url) {
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) {
                Util.getPlatform().openUri(URI.create(url));
            }
            // Return to the hub so the player can follow more than one link.
            Minecraft.getInstance().setScreen(this);
        }, url, true));
    }

    /** The clickable {@link Style} under the given mouse position, or null — used for inline links. */
    private Style styleAt(double mouseX, double mouseY) {
        int lh = this.font.lineHeight;
        for (TextBlock tb : textBlocks) {
            for (int i = 0; i < tb.descLines().size(); i++) {
                int lineY = tb.descY() + i * lh;
                if (mouseY < lineY || mouseY >= lineY + lh) continue;
                FormattedCharSequence line = tb.descLines().get(i);
                if (mouseX < colX || mouseX >= colX + this.font.width(line)) continue;
                return this.font.getSplitter().componentStyleAtWidth(line, (int) (mouseX - colX));
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            Style style = styleAt(mouseX, mouseY);
            if (style != null && style.getClickEvent() != null
                    && style.getClickEvent().getAction() == ClickEvent.Action.OPEN_URL) {
                openLink(style.getClickEvent().getValue());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Blurred menu panorama (vanilla), then a translucent panel behind the
        // content column so text stays readable over the spinning background.
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(colX - PANEL_PAD, panelTop, colX + colW + PANEL_PAD, panelBottom, COLOUR_PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Draws the background (with our panel) and the button widgets.
        super.render(g, mouseX, mouseY, partialTick);

        int lh = this.font.lineHeight;
        g.drawCenteredString(this.font, this.title, this.width / 2, TOP, COLOUR_HEADER);

        for (int i = 0; i < subtitleLines.size(); i++) {
            FormattedCharSequence line = subtitleLines.get(i);
            int lineX = this.width / 2 - this.font.width(line) / 2;
            g.drawString(this.font, line, lineX, subtitleY + i * lh, COLOUR_DESC, false);
        }

        for (TextBlock tb : textBlocks) {
            g.drawString(this.font, tb.header(), colX, tb.headerY(), COLOUR_HEADER, false);
            for (int i = 0; i < tb.descLines().size(); i++) {
                g.drawString(this.font, tb.descLines().get(i), colX, tb.descY() + i * lh, COLOUR_DESC, false);
            }
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
