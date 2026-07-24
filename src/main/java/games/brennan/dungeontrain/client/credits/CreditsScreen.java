package games.brennan.dungeontrain.client.credits;

import games.brennan.dungeontrain.client.localization.TranslationContributor;
import games.brennan.dungeontrain.client.localization.TranslationContributorsRegistry;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.support.SupportScreen;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The <b>Credits</b> page, opened from the title-screen book icon (see
 * {@code TitleScreenCreditsButton}). A vertically-scrolling column over the
 * blurred menu panorama, organised into three sections:
 *
 * <ol>
 *   <li><b>Created by</b> — Brennan Hatton, with an inline link to the original
 *       itch.io game.</li>
 *   <li><b>Built with</b> — Minecraft/NeoForge, Sable physics, bundled libraries
 *       (static copy).</li>
 *   <li><b>Translations</b> — every translator credit loaded by
 *       {@link LocalizationCreditRegistry} (across all locales, not just the
 *       selected one), each name clickable when the credit carries a URL. The
 *       whole section is omitted on stock installs where no credits exist.</li>
 * </ol>
 *
 * <p>The content column is laid out once in {@link #init} into a flat list of
 * positioned {@link Line}s (canvas-relative Y), then drawn in a scissor-clipped
 * viewport in {@link #render} offset by {@link #scrollY}. Inline links are
 * hit-tested in {@link #mouseClicked} against the same lines and opened through
 * vanilla's {@link ConfirmLinkScreen}, returning to this page. The {@code Done}
 * button is fixed below the viewport.</p>
 */
public final class CreditsScreen extends Screen {


    private static final int MAX_COL_W  = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int PANEL_PAD  = 10;
    private static final int TOP        = 16;
    private static final int HEADER_GAP = 3;
    private static final int DESC_GAP   = 4;
    private static final int SECTION_GAP = 10;
    private static final int SCROLL_STEP = 12;

    private static final int COLOUR_PANEL  = 0xC0101010;
    private static final int COLOUR_HEADER = 0xFFFFFFFF;
    private static final int COLOUR_DESC   = 0xFFCACACA;
    /** Blue used for inline links (RGB, no alpha). */
    private static final int COLOUR_LINK   = 0x5B9BFF;

    private final Screen parent;

    // Computed in init(), consumed in render()/click handling.
    private int colX;
    private int colW;
    private int viewportTop;
    private int viewportBottom;
    private int contentHeight;
    private int scrollY;
    private int maxScroll;
    private final List<Line> lines = new ArrayList<>();

    /**
     * One laid-out text line at a canvas-relative Y. {@code centered} lines are
     * horizontally centred on the screen (title/subtitle); the rest are
     * left-aligned at {@link #colX}. The {@link FormattedCharSequence} carries any
     * inline-link {@link Style}, so both drawing and hit-testing use it directly.
     */
    private record Line(FormattedCharSequence text, int canvasY, boolean centered, int colour) {}

    public CreditsScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.credits.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        lines.clear();
        colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        colX = (this.width - colW) / 2;
        int lh = this.font.lineHeight;

        int y = 0;

        // Title + subtitle, centred.
        y = addCentered(this.title, y, lh, COLOUR_HEADER);
        y += 6;
        y = addCenteredWrapped(Component.translatable("gui.dungeontrain.credits.subtitle"), y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        // Made by — the credited team.
        y = addLeft(Component.translatable("gui.dungeontrain.credits.team.header"), y, lh, COLOUR_HEADER);
        y += HEADER_GAP;
        y = addLeftWrapped(person(
                Component.literal("Brennan Hatton"),
                Component.translatable("gui.dungeontrain.credits.team.designer")), y, lh, COLOUR_DESC);
        y = addLeftWrapped(person(
                Component.literal("Wilson Taylor"),
                Component.translatable("gui.dungeontrain.credits.team.narrative")), y, lh, COLOUR_DESC);
        y += SECTION_GAP;

        // Built with — static copy.
        y = addSection(y, lh,
                Component.translatable("gui.dungeontrain.credits.built_with.header"),
                Component.translatable("gui.dungeontrain.credits.built_with.desc"));

        // Translations — the generated, human-grouped translator list (one line per person,
        // listing every language they worked on with a %). Fully derived from the provenance
        // data at build time, so it never needs a hand-authored credit file. Skipped when empty.
        List<TranslationContributor> contributors = TranslationContributorsRegistry.all();
        if (!contributors.isEmpty()) {
            y = addLeft(Component.translatable("gui.dungeontrain.credits.translations.header"), y, lh, COLOUR_HEADER);
            y += HEADER_GAP;
            y = addLeftWrapped(Component.translatable("gui.dungeontrain.credits.translations.desc"), y, lh, COLOUR_DESC);
            y += DESC_GAP;
            for (TranslationContributor contributor : contributors) {
                y = addLeftWrapped(personLine(contributor), y, lh, COLOUR_DESC);
            }
            y += SECTION_GAP;
        }

        contentHeight = y;

        // One bottom row: "Support the Developer" beside Done. The viewport ends just
        // above the row so scrolling content never overlaps the buttons.
        int rowY = this.height - 28;
        viewportTop = TOP;
        viewportBottom = rowY - 8;
        if (viewportBottom < viewportTop) {
            viewportBottom = viewportTop;
        }
        maxScroll = Math.max(0, contentHeight - (viewportBottom - viewportTop));
        scrollY = Mth.clamp(scrollY, 0, maxScroll);

        int gap = 4;
        int supportW = 150;
        int doneW = 100;
        int rowX = (this.width - (supportW + gap + doneW)) / 2;

        // Shortcut to the "Ways to Help" hub; parent is this page so its Done returns here.
        addRenderableWidget(new DarkTintedButton(rowX, rowY, supportW, 20,
                Component.translatable("gui.dungeontrain.credits.support_button"),
                b -> Minecraft.getInstance().setScreen(new SupportScreen(this))));

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(rowX + supportW + gap, rowY, doneW, 20)
                .build());
    }

    /** "&lt;name&gt; — &lt;role&gt;" for the Made-by list; the name may be an inline link. */
    private static Component person(Component name, Component role) {
        return Component.translatable("gui.dungeontrain.credits.team.person", name, role);
    }

    /** Header text + wrapped description, returning the canvas Y just below the section. */
    private int addSection(int y, int lh, Component header, Component desc) {
        y = addLeft(header, y, lh, COLOUR_HEADER);
        y += HEADER_GAP;
        y = addLeftWrapped(desc, y, lh, COLOUR_DESC);
        return y + SECTION_GAP;
    }

    private int addCentered(Component text, int y, int lh, int colour) {
        lines.add(new Line(text.getVisualOrderText(), y, true, colour));
        return y + lh;
    }

    private int addCenteredWrapped(Component text, int y, int lh, int colour) {
        for (FormattedCharSequence line : this.font.split(text, colW)) {
            lines.add(new Line(line, y, true, colour));
            y += lh;
        }
        return y;
    }

    private int addLeft(Component text, int y, int lh, int colour) {
        lines.add(new Line(text.getVisualOrderText(), y, false, colour));
        return y + lh;
    }

    private int addLeftWrapped(Component text, int y, int lh, int colour) {
        for (FormattedCharSequence line : this.font.split(text, colW)) {
            lines.add(new Line(line, y, false, colour));
            y += lh;
        }
        return y;
    }

    /**
     * "&lt;Name&gt; — &lt;Language&gt; (P%), &lt;Language&gt; (P%)": one line per contributor, their
     * languages in the generated (strongest-share-first) order. The name links when the
     * contributor has a URL.
     */
    private Component personLine(TranslationContributor contributor) {
        Component nameComp = contributor.url()
                .map(u -> link(Component.literal(contributor.name()), u))
                .orElseGet(() -> Component.literal(contributor.name()));

        MutableComponent langs = Component.empty();
        boolean first = true;
        for (TranslationContributor.LanguageShare share : contributor.languages()) {
            if (!first) {
                langs.append(", ");
            }
            langs.append(languagePercent(share));
            first = false;
        }
        return Component.translatable("gui.dungeontrain.credits.translations.person_line", nameComp, langs);
    }

    /** "&lt;Language&gt; (P%)" for one of a contributor's languages. */
    private Component languagePercent(TranslationContributor.LanguageShare share) {
        LanguageInfo info = Minecraft.getInstance().getLanguageManager().getLanguage(share.locale());
        Component language = info != null ? info.toComponent() : Component.literal(share.locale());
        // At least 1% so a small-but-real contribution never reads as "0%". The "%" lives in the
        // literal (not the translation format) so no locale has to escape it.
        int percent = Math.max(1, (int) Math.round(share.fraction() * 100));
        return Component.translatable("gui.dungeontrain.credits.translations.lang_percent",
                language, Component.literal(percent + "%"));
    }

    /** Style {@code label} as a blue, underlined, click-to-open-URL inline link. */
    private static Component link(MutableComponent label, String url) {
        return label.withStyle(s -> s
                .withColor(COLOUR_LINK)
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

    /** The clickable {@link Style} under the given mouse position within the scrolled viewport, or null. */
    private Style styleAt(double mouseX, double mouseY) {
        if (mouseY < viewportTop || mouseY >= viewportBottom) {
            return null;
        }
        int lh = this.font.lineHeight;
        double canvasY = mouseY - viewportTop + scrollY;
        for (Line line : lines) {
            if (canvasY < line.canvasY() || canvasY >= line.canvasY() + lh) {
                continue;
            }
            int lineWidth = this.font.width(line.text());
            int startX = line.centered() ? this.width / 2 - lineWidth / 2 : colX;
            if (mouseX < startX || mouseX >= startX + lineWidth) {
                continue;
            }
            return this.font.getSplitter().componentStyleAtWidth(line.text(), (int) (mouseX - startX));
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0) {
            this.scrollY = Mth.clamp(this.scrollY - (int) (scrollY * SCROLL_STEP), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Blurred menu panorama (vanilla), then a translucent panel behind the
        // scrolling viewport so text stays readable over the spinning background.
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(colX - PANEL_PAD, viewportTop - PANEL_PAD,
                colX + colW + PANEL_PAD, viewportBottom + PANEL_PAD, COLOUR_PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Draws the background (with our panel) and the Done widget.
        super.render(g, mouseX, mouseY, partialTick);

        int lh = this.font.lineHeight;
        g.enableScissor(colX - PANEL_PAD, viewportTop, colX + colW + PANEL_PAD, viewportBottom);
        for (Line line : lines) {
            int drawY = viewportTop + line.canvasY() - scrollY;
            if (drawY + lh < viewportTop || drawY > viewportBottom) {
                continue; // cull off-viewport lines
            }
            int x = line.centered()
                    ? this.width / 2 - this.font.width(line.text()) / 2
                    : colX;
            g.drawString(this.font, line.text(), x, drawY, line.colour(), false);
        }
        g.disableScissor();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
