package games.brennan.dungeontrain.client.credits;

import games.brennan.dungeontrain.client.localization.TranslationContributor;
import games.brennan.dungeontrain.client.localization.TranslationCreditsMerge;
import games.brennan.dungeontrain.client.menu.AiPolicyIconButton;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.policy.AiPolicyScreen;
import games.brennan.dungeontrain.client.support.SupportScreen;
import games.brennan.dungeontrain.client.ui.CardCanvas;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;

import java.net.URI;
import java.util.List;

/**
 * The <b>Credits</b> page, opened from the title-screen book icon (see
 * {@code TitleScreenCreditsButton}). A vertically-scrolling column over the blurred menu panorama,
 * organised into cards:
 *
 * <ol>
 *   <li><b>Made by</b> — Brennan as the large lead card row, Wilson as a small secondary credit
 *       below him, the two separated by a hairline rather than nested boxes.</li>
 *   <li><b>Translations</b> — every translator credit from {@link TranslationCreditsMerge} (the
 *       build-time list plus anyone the relay has approved since), each name clickable when the
 *       credit carries a URL. The whole card is omitted on stock installs where no credits exist,
 *       which is the normal en_us release-build path rather than an edge case.</li>
 * </ol>
 *
 * <p>Scrolling, clipping, the card/rule/photo draw order, inline-link hit-testing and the palette
 * all live in {@link CardCanvas}, shared with the AI Policy page so the two cannot drift apart —
 * which they did once already, this page being the copy that one was made from. This class is only
 * the content and the bottom button row.</p>
 */
public final class CreditsScreen extends Screen {

    private static final int MAX_COL_W   = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int TOP         = 16;
    private static final int DESC_GAP    = 4;

    /** Amber for the people who made it — the same accent the death screen titles use. */
    private static final int ACCENT_TEAM = 0xFFE0B56A;
    /** Green for the translators. */
    private static final int ACCENT_TRANSLATIONS = 0xFF5FBF5F;

    /** Team photos are 128×128 sources. */
    private static final int TEX = 128;
    /** Lead creator (Brennan) gets a large photo; the secondary credit (Wilson) a small one. */
    private static final int PHOTO_LEAD = 72;
    private static final int PHOTO_SUB = 32;
    /** Vertical gap either side of the hairline between the two team rows. */
    private static final int CARD_ROW_GAP = 7;

    private static final ResourceLocation BRENNAN_PHOTO =
            ResourceLocation.fromNamespaceAndPath("dungeontrain", "textures/gui/credits/brennan.png");
    private static final ResourceLocation WILSON_PHOTO =
            ResourceLocation.fromNamespaceAndPath("dungeontrain", "textures/gui/credits/wilson.png");

    private final Screen parent;
    private final CardCanvas canvas;

    public CreditsScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.credits.title"));
        this.parent = parent;
        this.canvas = new CardCanvas(Minecraft.getInstance().font);
    }

    @Override
    protected void init() {
        int colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        canvas.beginLayout((this.width - colW) / 2, colW);

        int y = 0;

        // Title + subtitle, centred and un-carded — they frame the page.
        y = canvas.addCentered(this.title, y, CardCanvas.COLOUR_HEADER);
        y += CardCanvas.PARA_GAP;
        y = canvas.addCenteredWrapped(Component.translatable("gui.dungeontrain.credits.subtitle"),
                y, CardCanvas.COLOUR_DESC);
        y += CardCanvas.SECTION_GAP;

        y = addTeamCard(y);

        // The generated, human-grouped translator list (one line per person, listing every language
        // they worked on with a %). Fully derived from the provenance data at build time, so it
        // never needs a hand-authored credit file. The build-time list PLUS anyone the relay has
        // approved since — see TranslationCreditsMerge for why they are merged into one list rather
        // than thanked twice in two. Skipped entirely when empty, so no empty card is drawn.
        List<TranslationContributor> contributors = TranslationCreditsMerge.merged();
        if (!contributors.isEmpty()) {
            y += CardCanvas.CARD_GAP;
            y = addTranslationsCard(contributors, y);
        }

        // One bottom row: "Support the Developer" and the AI Policy icon beside Done. The viewport
        // ends just above the row so scrolling content never overlaps the buttons.
        int rowY = this.height - 28;
        canvas.finishLayout(y, TOP, rowY - 8);

        int gap = 4;
        int supportW = 150;
        // Square, so it costs the row only its own height — the two text buttons keep their widths.
        int policyW = 20;
        int doneW = 100;
        int rowX = (this.width - (supportW + gap + policyW + gap + doneW)) / 2;

        // Shortcut to the "Ways to Help" hub; parent is this page so its Done returns here.
        addRenderableWidget(new DarkTintedButton(rowX, rowY, supportW, 20,
                Component.translatable("gui.dungeontrain.credits.support_button"),
                b -> Minecraft.getInstance().setScreen(new SupportScreen(this))));

        // "Who made this" and "was any of it made by AI" are the same question, so the AI Policy
        // sits on the page that answers the first half. Also reachable from Dungeon Train Options.
        // An icon rather than a labelled button: the row already carries two of those, and the
        // glyph is unlabelled, so the tooltip below is what names it — it is not optional.
        Component policyLabel = Component.translatable("gui.dungeontrain.credits.ai_policy_button");
        AiPolicyIconButton policy = new AiPolicyIconButton(rowX + supportW + gap, rowY, policyW,
                policyLabel, b -> Minecraft.getInstance().setScreen(new AiPolicyScreen(this)));
        policy.setTooltip(Tooltip.create(policyLabel));
        addRenderableWidget(policy);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(rowX + supportW + gap + policyW + gap, rowY, doneW, 20)
                .build());
    }

    /** The "Made by" card: heading, accent bar, then the two people separated by a hairline. */
    private int addTeamCard(int top) {
        int innerX = canvas.colX() + CardCanvas.CARD_PAD;
        int innerW = Math.max(1, canvas.colW() - CardCanvas.CARD_PAD * 2);
        int y = top + CardCanvas.CARD_PAD;

        y = canvas.addWrappedAt(Component.translatable("gui.dungeontrain.credits.team.header"),
                innerX, innerW, y, CardCanvas.COLOUR_HEADER);
        y += CardCanvas.RULE_GAP;
        y = canvas.addRule(innerX, y, Math.min(CardCanvas.RULE_W, innerW), ACCENT_TEAM);
        y += CardCanvas.RULE_TO_BODY;

        y = addPersonRow(innerX, innerW, Math.min(PHOTO_LEAD, innerW), y, BRENNAN_PHOTO,
                "Brennan Hatton", "gui.dungeontrain.credits.team.designer",
                "gui.dungeontrain.credits.team.brennan.bio");

        y += CARD_ROW_GAP;
        y = canvas.addDivider(innerX, y, innerW);
        y += CARD_ROW_GAP;

        y = addPersonRow(innerX, innerW, Math.min(PHOTO_SUB, innerW), y, WILSON_PHOTO,
                "Wilson Taylor", "gui.dungeontrain.credits.team.narrative",
                "gui.dungeontrain.credits.team.wilson.bio");

        y += CardCanvas.CARD_PAD;
        canvas.addCard(top, y - top);
        return y;
    }

    /**
     * One person inside the team card: photo on the left, name / role / wrapped bio in the column
     * beside it. Returns the Y just below the row — the taller of the photo and the text block.
     */
    private int addPersonRow(int x, int w, int photo, int y, ResourceLocation tex,
                             String name, String roleKey, String bioKey) {
        canvas.addImg(tex, x, y, photo, photo, TEX);
        int textX = x + photo + CardCanvas.ICON_GAP;
        int textW = Math.max(1, w - photo - CardCanvas.ICON_GAP);

        int ty = canvas.addLineAt(Component.literal(name).getVisualOrderText(), textX, y,
                CardCanvas.COLOUR_HEADER);
        ty = canvas.addLineAt(Component.translatable(roleKey).getVisualOrderText(), textX, ty,
                CardCanvas.COLOUR_DESC);
        ty += 2;
        ty = canvas.addWrappedAt(Component.translatable(bioKey), textX, textW, ty,
                CardCanvas.COLOUR_DESC);
        return Math.max(y + photo, ty);
    }

    /** The "Translations" card: heading, accent bar, the thank-you line, then one line per person. */
    private int addTranslationsCard(List<TranslationContributor> contributors, int top) {
        int innerX = canvas.colX() + CardCanvas.CARD_PAD;
        int innerW = Math.max(1, canvas.colW() - CardCanvas.CARD_PAD * 2);
        int y = top + CardCanvas.CARD_PAD;

        y = canvas.addWrappedAt(Component.translatable("gui.dungeontrain.credits.translations.header"),
                innerX, innerW, y, CardCanvas.COLOUR_HEADER);
        y += CardCanvas.RULE_GAP;
        y = canvas.addRule(innerX, y, Math.min(CardCanvas.RULE_W, innerW), ACCENT_TRANSLATIONS);
        y += CardCanvas.RULE_TO_BODY;

        y = canvas.addWrappedAt(Component.translatable("gui.dungeontrain.credits.translations.desc"),
                innerX, innerW, y, CardCanvas.COLOUR_DESC);
        y += DESC_GAP;
        for (TranslationContributor contributor : contributors) {
            y = canvas.addWrappedAt(personLine(contributor), innerX, innerW, y,
                    CardCanvas.COLOUR_DESC);
        }

        y += CardCanvas.CARD_PAD;
        canvas.addCard(top, y - top);
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
        if (share.total() <= 0) {
            // A relay credit for a language whose totals this build knows nothing about. The name
            // and the language are real; the percentage would be invented, so it is left off.
            return language;
        }
        // At least 1% so a small-but-real contribution never reads as "0%", and never above 100%:
        // LanguageShare's contributed <= total invariant is the GENERATOR's, and a relay credit
        // counts approved submissions (books and narrative units included) against a denominator
        // that is only the locale's lang-key count. The "%" lives in the literal (not the
        // translation format) so no locale has to escape it.
        int percent = Math.min(100, Math.max(1, (int) Math.round(share.fraction() * 100)));
        return Component.translatable("gui.dungeontrain.credits.translations.lang_percent",
                language, Component.literal(percent + "%"));
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
        // Draws the background (with our panel) and the button row.
        super.render(g, mouseX, mouseY, partialTick);
        canvas.render(g, this.width);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
