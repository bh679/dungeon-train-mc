package games.brennan.dungeontrain.client.policy;

import games.brennan.dungeontrain.client.ui.CardCanvas;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.util.List;

/**
 * The <b>AI Policy</b> page — a plain statement of what in Dungeon Train is and is not made by AI,
 * reachable from the Dungeon Train Options screen and from the Credits page.
 *
 * <p>The page frames itself with un-carded prose — an intro at the top and a thank-you at the
 * bottom — and puts everything a player is actually scanning for into cards, one per section, laid
 * out from {@link AiPolicyContent}. Each card is an outlined panel with its heading over a short
 * accent bar, and each bullet carries a 16px item glyph in the margin. The colours do real work:
 * green for the list of things made by hand, amber for the honest disclosure of where AI is used.
 * The changelog bullet ends in a link to the Discord updates channel.</p>
 *
 * <p>Scrolling, clipping, the card/rule/glyph draw order, inline-link hit-testing and the palette
 * all live in {@link CardCanvas}, shared with the Credits page so the two cannot drift apart. This
 * class is only the content and the {@code Done} button.</p>
 */
public final class AiPolicyScreen extends Screen {

    private static final int MAX_COL_W   = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int TOP         = 16;

    /**
     * The {@code #minor-updates} Discord message the changelog line points at. A permalink to one
     * specific message rather than a server invite, so it is baked here rather than coming from
     * {@link games.brennan.dungeontrain.client.links.OfficialLinks} with the relay-served bases.
     */
    private static final String UPDATES_URL =
            "https://discord.com/channels/680177367381049356/693919099876671508/1541810418274668724";

    private final Screen parent;
    private final CardCanvas canvas;

    public AiPolicyScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.ai_policy.title"));
        this.parent = parent;
        this.canvas = new CardCanvas(Minecraft.getInstance().font);
    }

    @Override
    protected void init() {
        int colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        canvas.beginLayout((this.width - colW) / 2, colW);

        int y = 0;

        // Title + subtitle, centred.
        y = canvas.addCentered(this.title, y, CardCanvas.COLOUR_HEADER);
        y += CardCanvas.PARA_GAP;
        y = canvas.addCenteredWrapped(Component.translatable("gui.dungeontrain.ai_policy.subtitle"),
                y, CardCanvas.COLOUR_DESC);
        y += CardCanvas.SECTION_GAP;

        // Intro — un-carded on purpose: it frames the page rather than being a section of it.
        y = canvas.addLeftWrapped(Component.translatable("gui.dungeontrain.ai_policy.intro.1"),
                y, CardCanvas.COLOUR_DESC);
        y += CardCanvas.PARA_GAP;
        y = canvas.addLeftWrapped(Component.translatable("gui.dungeontrain.ai_policy.intro.2"),
                y, CardCanvas.COLOUR_DESC);
        y += CardCanvas.SECTION_GAP;

        List<AiPolicyContent.Section> sections = AiPolicyContent.sections();
        for (int i = 0; i < sections.size(); i++) {
            y = addCard(sections.get(i), y);
            if (i < sections.size() - 1) {
                y += CardCanvas.CARD_GAP;
            }
        }
        y += CardCanvas.SECTION_GAP;

        y = canvas.addLeftWrapped(Component.translatable("gui.dungeontrain.ai_policy.closing"),
                y, CardCanvas.COLOUR_DESC);
        y += CardCanvas.PARA_GAP;
        y = canvas.addLeftWrapped(Component.translatable("gui.dungeontrain.ai_policy.signature"),
                y, CardCanvas.COLOUR_HEADER);

        // The viewport ends just above the Done button so scrolling content never overlaps it.
        int rowY = this.height - 28;
        canvas.finishLayout(y, TOP, rowY - 8);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 50, rowY, 100, 20)
                .build());
    }

    /**
     * Lay out one section as a card: heading, accent bar, then either a wrapped paragraph or the
     * bullet rows. Returns the canvas Y just below the card's bottom border.
     */
    private int addCard(AiPolicyContent.Section section, int top) {
        int lh = canvas.lineHeight();
        int innerX = canvas.colX() + CardCanvas.CARD_PAD;
        int innerW = Math.max(1, canvas.colW() - CardCanvas.CARD_PAD * 2);
        int y = top + CardCanvas.CARD_PAD;

        // Wrapped rather than a single line: at GUI Scale 4 a heading can be wider than the
        // card, and a heading running out past its own border is the one overflow a reader
        // cannot miss.
        y = canvas.addWrappedAt(Component.translatable(section.headerKey()), innerX, innerW, y,
                CardCanvas.COLOUR_HEADER);
        y += CardCanvas.RULE_GAP;
        y = canvas.addRule(innerX, y, Math.min(CardCanvas.RULE_W, innerW), section.accent());
        y += CardCanvas.RULE_TO_BODY;

        if (section.isParagraph()) {
            y = canvas.addWrappedAt(Component.translatable(section.body()), innerX, innerW, y,
                    CardCanvas.COLOUR_DESC);
        } else {
            int textX = innerX + CardCanvas.ICON + CardCanvas.ICON_GAP;
            int textW = Math.max(1, innerW - CardCanvas.ICON - CardCanvas.ICON_GAP);
            List<AiPolicyContent.Bullet> bullets = section.bullets();
            for (int i = 0; i < bullets.size(); i++) {
                AiPolicyContent.Bullet bullet = bullets.get(i);
                // Centre the glyph on the row's FIRST line, not on the whole row — a three-line
                // bullet with a vertically-centred icon reads as though the icon belongs to the
                // middle line.
                int iconTop = y - (CardCanvas.ICON - lh) / 2;
                canvas.addIcon(new ItemStack(bullet.glyph()), innerX, iconTop);

                int textBottom = canvas.addWrappedAt(bulletText(bullet), textX, textW, y,
                        CardCanvas.COLOUR_DESC);
                y = Math.max(textBottom, iconTop + CardCanvas.ICON);
                if (i < bullets.size() - 1) {
                    y += CardCanvas.ROW_GAP;
                }
            }
        }

        y += CardCanvas.CARD_PAD;
        canvas.addCard(top, y - top);
        return y;
    }

    /**
     * A bullet's text. The changelog line is the one that takes an argument — a link to the Discord
     * channel those changelogs are posted in.
     */
    private static Component bulletText(AiPolicyContent.Bullet bullet) {
        if ("gui.dungeontrain.ai_policy.used.3".equals(bullet.key())) {
            return Component.translatable(bullet.key(),
                    link(Component.translatable("gui.dungeontrain.ai_policy.used.3.link"), UPDATES_URL));
        }
        return Component.translatable(bullet.key());
    }

    /** Style {@code label} as a blue, underlined, click-to-open-URL inline link. */
    private static Component link(MutableComponent label, String url) {
        return label.withStyle(s -> s
                .withColor(CardCanvas.COLOUR_LINK)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
    }

    /** Open {@code url} through the vanilla confirm screen, returning to this page either way. */
    private void openLink(String url) {
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) {
                Util.getPlatform().openUri(URI.create(url));
            }
            Minecraft.getInstance().setScreen(this);
        }, url, true));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            Style style = canvas.styleAt(mouseX, mouseY, this.width);
            if (style != null && style.getClickEvent() != null
                    && style.getClickEvent().getAction() == ClickEvent.Action.OPEN_URL) {
                openLink(style.getClickEvent().getValue());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return canvas.scroll(scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Blurred menu panorama (vanilla), then the canvas's own translucent panel so text stays
        // readable over the spinning background.
        super.renderBackground(g, mouseX, mouseY, partialTick);
        canvas.renderPanel(g);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Draws the background (with our panel) and the Done widget.
        super.render(g, mouseX, mouseY, partialTick);
        canvas.render(g, this.width);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
