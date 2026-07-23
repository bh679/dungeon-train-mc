package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.menu.ColorTintedButton;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.fml.ModList;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * "Ways to Help" hub, opened from the title-screen support button (see
 * {@link TitleScreenSupportButton}). A full-screen panel organised into the
 * three ways a player can support Dungeon Train:
 *
 * <ol>
 *   <li><b>Financial Support</b> — a Direct Donation button (Revolut) + the
 *       orange Patreon button, with the Kinetic Hosting affiliate (the referral
 *       shipped in the modpack's {@code khi.toml}) as an inline link.</li>
 *   <li><b>Share the Mod</b> — text only.</li>
 *   <li><b>Feedback &amp; Testing</b> — text only, with an inline clickable
 *       "Discord" link ("Join the Discord") to get involved.</li>
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

    // All four outbound links come from OfficialLinks — relay-served when reachable, baked
    // fallbacks offline. Read in init(), which reruns on every open, so a fetch that lands
    // after the title screen built still applies here.

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
    private static final float[] TINT_ORANGE = {1.00F, 0.47F, 0.38F}; // Patreon
    private static final float[] TINT_GREEN  = {0.30F, 0.80F, 0.35F}; // Direct donation

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

    /**
     * One link button in a section: label key + target URL + optional sprite tint
     * (null = default grey) + optional hover-tooltip key (null = none) + the
     * analytics target name ({@link UiAnalytics} enum) for click/confirm events.
     */
    private record LinkButton(String labelKey, String url, float[] tint, String tooltipKey, String analyticsTarget) {}

    /** When this visit started — set once in the constructor (init() reruns on every resize). */
    private final long openedAtMs = System.currentTimeMillis();
    /** One-shot latch so Esc + Done + any future double-close report a single page_time. */
    private boolean timeReported;

    public SupportScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.support.title"));
        this.parent = parent;
        UiAnalytics.pageOpen(UiAnalytics.SURFACE_SUPPORT_PAGE);
    }

    /**
     * Companion mods that ship via the Dungeon Train modpack but are not DT
     * dependencies (the sibling mods always load, so they can't discriminate).
     * Two or more of these present is read as "the player is running the
     * modpack" and switches the copy from mod- to modpack-wording.
     */
    private static final String[] MODPACK_COMPANIONS =
            {"appleskin", "ferritecore", "modernfix", "advancementplaques"};

    /** Heuristic: is this a modpack install rather than the bare mod? */
    private static boolean isModpackInstall() {
        int found = 0;
        for (String id : MODPACK_COMPANIONS) {
            if (ModList.get().isLoaded(id) && ++found >= 2) return true;
        }
        return false;
    }

    /**
     * Resolve a copy key to its {@code .modpack} variant when this looks like a
     * modpack install and the current language actually has that variant —
     * otherwise the base key, so untranslated languages fall back cleanly.
     */
    private static String copyKey(String baseKey) {
        String modpackKey = baseKey + ".modpack";
        return isModpackInstall() && I18n.exists(modpackKey) ? modpackKey : baseKey;
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

        // Financial — a Direct Donation button (Revolut) then the orange Patreon
        // button; the affiliate is an inline link spliced into the copy.
        y = addSection(y, lh,
                Component.translatable("gui.dungeontrain.support.financial.header"),
                Component.translatable(copyKey("gui.dungeontrain.support.financial.desc"), affiliateLink()),
                new LinkButton("gui.dungeontrain.support.financial.donate", revolutUrl(), TINT_GREEN,
                        "gui.dungeontrain.support.donate_tooltip", UiAnalytics.TARGET_DONATE),
                new LinkButton("gui.dungeontrain.support.financial.patreon", OfficialLinks.patreon(), TINT_ORANGE, null,
                        UiAnalytics.TARGET_PATREON));

        // Share — text only, no link.
        y = addSection(y, lh,
                Component.translatable("gui.dungeontrain.support.share.header"),
                Component.translatable(copyKey("gui.dungeontrain.support.share.desc")));

        // Feedback — only the final "Discord" (Join the Discord) is a clickable link.
        y = addSection(y, lh,
                Component.translatable("gui.dungeontrain.support.feedback.header"),
                Component.translatable(copyKey("gui.dungeontrain.support.feedback.desc"), discordLink()));

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
        Button.OnPress onPress = b -> openLink(lb.url(), lb.analyticsTarget());
        float[] t = lb.tint();
        Button button = (t == null)
                ? new DarkTintedButton(x, y, w, h, label, onPress)
                : new ColorTintedButton(x, y, w, h, label, t[0], t[1], t[2], onPress);
        if (lb.tooltipKey() != null) {
            button.setTooltip(Tooltip.create(Component.translatable(lb.tooltipKey())));
        }
        return button;
    }

    /**
     * The direct-donation URL. When the base (relay-served or baked Revolut link) carries a
     * {@code note=} field the player's name is URL-encoded onto it, matching the historical
     * Revolut behaviour; a relay-rotated provider without a note field is used verbatim so the
     * suffix can't corrupt an unknown URL shape.
     */
    private String revolutUrl() {
        String base = OfficialLinks.payment();
        if (!base.contains("note=")) return base;
        String encoded = URLEncoder.encode(playerName(), StandardCharsets.UTF_8).replace("+", "%20");
        return base + encoded;
    }

    private static String playerName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getUser() != null ? mc.getUser().getName() : "Player";
    }

    /** A clickable, blue, underlined "Discord" word for splicing into description copy. */
    private Component discordLink() {
        return link(Component.literal("Discord"), OfficialLinks.discord());
    }

    /** A clickable, blue, underlined "affiliate link" phrase for the Financial copy. */
    private Component affiliateLink() {
        return link(Component.translatable("gui.dungeontrain.support.financial.affiliate_link"), OfficialLinks.affiliate());
    }

    /** Style {@code label} as a blue, underlined, click-to-open-URL inline link. */
    private static Component link(net.minecraft.network.chat.MutableComponent label, String url) {
        return label.withStyle(s -> s
                .withColor(COLOUR_LINK)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
    }

    /**
     * Open {@code url} through the vanilla confirm screen, recording the click and whether the
     * player followed through. {@code analyticsTarget} is one of the {@link UiAnalytics} target
     * names, or null to open untracked (an inline URL we don't recognise).
     */
    private void openLink(String url, String analyticsTarget) {
        if (analyticsTarget != null) {
            UiAnalytics.click(UiAnalytics.SURFACE_SUPPORT_PAGE, analyticsTarget);
        }
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            if (analyticsTarget != null) {
                UiAnalytics.confirm(UiAnalytics.SURFACE_SUPPORT_PAGE, analyticsTarget, yes);
            }
            if (yes) {
                Util.getPlatform().openUri(URI.create(url));
            }
            // Return to the hub so the player can follow more than one link.
            Minecraft.getInstance().setScreen(this);
        }, url, true));
    }

    /**
     * Analytics target name for an inline link's URL — the two inline links are built from
     * {@link OfficialLinks}, so an exact match identifies them; anything else is untracked.
     */
    private static String inlineTarget(String url) {
        if (url == null) return null;
        if (url.equals(OfficialLinks.discord())) return UiAnalytics.TARGET_DISCORD;
        if (url.equals(OfficialLinks.affiliate())) return UiAnalytics.TARGET_AFFILIATE;
        return null;
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
                String url = style.getClickEvent().getValue();
                openLink(url, inlineTarget(url));
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
        if (!timeReported) {
            timeReported = true;
            UiAnalytics.pageTime(UiAnalytics.SURFACE_SUPPORT_PAGE, System.currentTimeMillis() - openedAtMs);
        }
        Minecraft.getInstance().setScreen(parent);
    }
}
