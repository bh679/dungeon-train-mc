package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
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
 *   <li><b>Financial Support</b> — Patreon + the Kinetic Hosting affiliate link
 *       (the same referral shipped in the modpack's {@code khi.toml}).</li>
 *   <li><b>Share the Mod</b> — the Modrinth/CurseForge pages (easy to send to
 *       creators) and a nudge to post videos/streams in Discord.</li>
 *   <li><b>Feedback &amp; Testing</b> — join the Discord to share ideas and
 *       stream playthroughs.</li>
 * </ol>
 *
 * <p>Each link button wraps the URL in vanilla's {@link ConfirmLinkScreen} —
 * the standard "open external link?" confirmation — matching
 * {@code TitleScreenLayoutHandler#openDiscord}. Confirming or cancelling returns
 * to <em>this</em> hub (not the title screen) so the player can follow several
 * links in one visit.</p>
 *
 * <p>Section headers and descriptions are drawn in {@link #render} over a
 * translucent contrast panel painted in {@link #renderBackground}; the link
 * buttons are real widgets added in {@link #init} so they draw on top of that
 * panel.</p>
 */
public final class SupportScreen extends Screen {

    private static final String PATREON_URL    = "https://www.patreon.com/brennanhatton";
    private static final String AFFILIATE_URL  = "https://billing.kinetichosting.com/aff.php?aff=1461";
    private static final String MODRINTH_URL   = "https://modrinth.com/mod/dungeon-train";
    private static final String CURSEFORGE_URL = "https://www.curseforge.com/minecraft/mc-mods/dungeon-train";
    private static final String DISCORD_URL    = "https://discord.gg/jdKAwb6rbW";

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

    /** One link button in a section: label key + target URL. */
    private record LinkButton(String labelKey, String url) {}

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
                "gui.dungeontrain.support.financial.header",
                "gui.dungeontrain.support.financial.desc",
                new LinkButton("gui.dungeontrain.support.financial.patreon", PATREON_URL),
                new LinkButton("gui.dungeontrain.support.financial.affiliate", AFFILIATE_URL));

        y = addSection(y, lh,
                "gui.dungeontrain.support.share.header",
                "gui.dungeontrain.support.share.desc",
                new LinkButton("gui.dungeontrain.support.share.modrinth", MODRINTH_URL),
                new LinkButton("gui.dungeontrain.support.share.curseforge", CURSEFORGE_URL));

        y = addSection(y, lh,
                "gui.dungeontrain.support.feedback.header",
                "gui.dungeontrain.support.feedback.desc",
                new LinkButton("gui.dungeontrain.support.feedback.discord", DISCORD_URL));

        panelBottom = y + (PANEL_PAD - SECTION_GAP);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());
    }

    /**
     * Lay out one section (header text + wrapped description + a row of link
     * buttons), registering the text for {@link #render} and the buttons as
     * live widgets. Returns the Y just below the section.
     */
    private int addSection(int y, int lh, String headerKey, String descKey, LinkButton... links) {
        int headerY = y;
        y += lh + HEADER_GAP;

        List<FormattedCharSequence> descLines =
                this.font.split(Component.translatable(descKey), colW);
        int descY = y;
        y += descLines.size() * lh + DESC_GAP;

        textBlocks.add(new TextBlock(Component.translatable(headerKey), headerY, descLines, descY));

        int n = links.length;
        int each = n == 1 ? colW : (colW - (n - 1) * BUTTON_GAP) / n;
        for (int i = 0; i < n; i++) {
            LinkButton lb = links[i];
            int bx = colX + i * (each + BUTTON_GAP);
            addRenderableWidget(new DarkTintedButton(bx, y, each, BUTTON_H,
                    Component.translatable(lb.labelKey()), b -> openLink(lb.url())));
        }
        return y + BUTTON_H + SECTION_GAP;
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
