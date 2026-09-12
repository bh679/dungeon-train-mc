package games.brennan.dungeontrain.client.credits;

import games.brennan.dungeontrain.client.chat.RelayChatClient;
import games.brennan.dungeontrain.client.credits.CreditEditClient.Action;
import games.brennan.dungeontrain.client.credits.CreditEditClient.Section;
import games.brennan.dungeontrain.client.localization.TranslationContributor;
import games.brennan.dungeontrain.client.localization.TranslationContributorsRegistry;
import games.brennan.dungeontrain.client.localization.TranslationCreditsMerge;
import games.brennan.dungeontrain.client.localization.LocalizationCreditRegistry;
import games.brennan.dungeontrain.client.localization.edit.TranslationCoverageClient;
import games.brennan.dungeontrain.client.localization.edit.TranslatorName;
import games.brennan.dungeontrain.client.localization.edit.TranslatorOwnNames;
import games.brennan.dungeontrain.client.localization.edit.TranslatorRenames;
import games.brennan.dungeontrain.template.BuilderCredit;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
 *   <li><b>Writers</b> and <b>Builders</b> — the relay's most-praised writers and everyone credited
 *       as a shipped template's builder ({@link RelayWriters}, {@link TemplateBuilderCredits}).</li>
 * </ol>
 *
 * <p>Any line that is this player's own — a translator name they submitted under
 * ({@link TranslatorOwnNames}), the writer row the relay ranks their uuid at, a builder credit
 * carrying their uuid — gets an <b>Edit</b> button opening {@link CreditEditScreen}: rename, be
 * listed as Anonymous, or come back. What the relay has not caught up with yet is laid over by
 * {@link CreditsSelfEdits} (and {@link TranslatorRenames} for translator names).</p>
 *
 * <p>Scrolling, clipping, the card/rule/photo draw order, inline-link hit-testing and the palette
 * all live in {@link CardCanvas}, shared with the AI Policy page so the two cannot drift apart —
 * which they did once already, this page being the copy that one was made from. This class is only
 * the content and the bottom button row.</p>
 */
public final class CreditsScreen extends Screen {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final int MAX_COL_W   = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int TOP         = 16;
    private static final int DESC_GAP    = 4;

    /** Amber for the people who made it — the same accent the death screen titles use. */
    private static final int ACCENT_TEAM = 0xFFE0B56A;
    /** Green for the translators. */
    private static final int ACCENT_TRANSLATIONS = 0xFF5FBF5F;
    /** Copper for the builders — the colour of the train they built. */
    private static final int ACCENT_BUILDERS = 0xFFC98A5B;
    /** Parchment for the writers. */
    private static final int ACCENT_WRITERS = 0xFFD9C08A;

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

    /** The Edit button beside a translator's own name: a compact row-height button. */
    private static final int EDIT_W = 34;
    private static final int EDIT_H = 14;
    /** Space kept between a wrapped name line and its Edit button. */
    private static final int EDIT_GAP = 6;

    /**
     * An Edit button and the canvas Y of the line it belongs to. The button is a real widget for
     * input, but is drawn by hand after the canvas so it scrolls with its line and sits on top of
     * the card rather than under it — see {@link #render}.
     */
    private record EditSlot(DarkTintedButton button, int canvasY) {}

    private final Screen parent;
    private final CardCanvas canvas;
    /** Names this player submitted translations under; empty until the relay answers. */
    private Set<String> ownNames = Set.of();
    /** This player's profile uuid, undashed — what a builder credit or a relay row carries. */
    private final String ownUuid;
    /** Where the relay ranks this player among the writers; null until it answers (or unranked). */
    private RelayWriters.Standing writerStanding;
    /** How much of each long list is showing — survives a re-layout, not a fresh screen. */
    private CreditsPaging translatorsPaging = CreditsPaging.START;
    private CreditsPaging buildersPaging = CreditsPaging.START;
    private CreditsPaging writersPaging = CreditsPaging.START;
    /** In-page control links carry this prefix in a RUN_COMMAND click event; see {@link #mouseClicked}. */
    private static final String CONTROL_PREFIX = "dt:credits/";
    private boolean askedForOwnNames;
    private final List<EditSlot> editSlots = new ArrayList<>();

    public CreditsScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.credits.title"));
        this.parent = parent;
        this.canvas = new CardCanvas(Minecraft.getInstance().font);
        Minecraft mc = Minecraft.getInstance();
        this.ownUuid = mc != null && mc.getUser() != null && mc.getUser().getProfileId() != null
                ? BuilderCredit.normaliseUuid(mc.getUser().getProfileId().toString()) : "";
    }

    @Override
    protected void init() {
        editSlots.clear();
        CreditsSelfEdits.beginPage();
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
        List<TranslationContributor> contributors = TranslationCreditsMerge.merge(
                TranslationContributorsRegistry.all(), TranslationCoverageClient.allCredits(),
                LocalizationCreditRegistry::totalKeysFor, TranslatorRenames.snapshot(),
                CreditsSelfEdits.get().hidden() ? ownNames : Set.of());
        if (!contributors.isEmpty()) {
            y += CardCanvas.CARD_GAP;
            y = addTranslationsCard(contributors, y);
        }

        // The community's writers — the relay's Most Praised Writers board, past the bar
        // RelayWriters sets. Relay-only: a book is written on the relay, never in the jar.
        List<RelayWriters.Writer> writers = RelayWriters.current();
        if (!writers.isEmpty()) {
            y += CardCanvas.CARD_GAP;
            y = addWritersCard(writers, y);
        }

        // Everyone credited as the original builder of a template that ships with the mod — the
        // jar's own weights files PLUS whoever the relay has credited since this build was cut (see
        // TemplateBuilderCredits.merged). Skipped entirely when nobody is credited, so no empty card
        // is drawn.
        List<TemplateBuilderCredits.Builder> builders = TemplateBuilderCredits.merged();
        LOGGER.info("[DungeonTrain] Credits: builders card — {} bundled, {} from the relay, {} merged.",
                TemplateBuilderCredits.all().size(), RelayTemplateBuilders.current().size(), builders.size());
        if (!builders.isEmpty()) {
            y += CardCanvas.CARD_GAP;
            y = addBuildersCard(builders, y);
        }

        // One bottom row: "Support the Developer" and the AI Policy icon beside Done. The viewport
        // ends just above the row so scrolling content never overlaps the buttons.
        int rowY = this.height - 28;
        canvas.finishLayout(y, TOP, rowY - 8);
        CreditsSelfEdits.endPage();

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

        // Once per screen (not per re-layout — a See more press must not be a relay round trip):
        // which of these names are this player's, and who else the relay credits as a builder.
        // Each answer arrives later on the render thread and re-lays the page — the same shape as
        // the submit screen's history list. Consent off answers "none" to the first; the second is
        // anonymous and always asked.
        if (!askedForOwnNames) {
            askedForOwnNames = true;
            RelayTemplateBuilders.refresh(() -> Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().screen == this) rebuildWidgets();
            }));
            RelayWriters.refresh(() -> Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().screen == this) rebuildWidgets();
            }));
            TranslatorOwnNames.fetch(names -> {
                if (!names.equals(ownNames) && Minecraft.getInstance().screen == this) {
                    ownNames = names;
                    rebuildWidgets();
                }
            });
            // Which writer row is mine — the board is uuid-free, so the relay is asked where this
            // uuid stands and the row at that rank is the one. Carries the uuid: consent-gated.
            if (!ownUuid.isEmpty() && RelayChatClient.canConnect()) {
                RelayWriters.fetchStanding(ownUuid, standing -> Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) return;
                    writerStanding = standing;
                    rebuildWidgets();
                }));
            }
        }
    }

    /**
     * The relay accepted an edit. Remember it locally so the page shows it at once (the jar, the
     * cached relay credits and the relay's own boards all lag — see {@link CreditsSelfEdits} and
     * {@link TranslatorRenames}), refresh the live lists, and come back to a freshly laid-out page.
     */
    private void onEdited(Section section, Action action, String from, String to) {
        Set<String> names = new java.util.HashSet<>(ownNames);
        switch (action) {
            case RENAME -> {
                // One identity: every name the player translated under is now `to` on the relay
                // (translations.renameAll), so every own name folds into it here too.
                for (String old : ownNames) TranslatorRenames.record(old, to);
                if (!from.isEmpty()) TranslatorRenames.record(from, to);
                TranslatorName.set(to);
                names.clear();
                names.add(to);
                CreditsSelfEdits.recordRename(from, to);
            }
            case REMOVE -> CreditsSelfEdits.setHidden(true);
            case RESTORE -> CreditsSelfEdits.setHidden(false);
        }
        // Refetch WITHOUT clearing: the overlay already renders the change from the cached lists,
        // and an empty cache while the answer is in flight would drop them from the page.
        TranslationCoverageClient.refetch();
        RelayWriters.refresh(null);
        RelayTemplateBuilders.refresh(null);
        CreditsScreen fresh = new CreditsScreen(parent);
        fresh.ownNames = Set.copyOf(names);
        fresh.writerStanding = writerStanding;
        fresh.askedForOwnNames = true;
        Minecraft.getInstance().setScreen(fresh);
    }

    /**
     * One credited line, with an Edit button riding its first line when it is this player's own.
     * The text then wraps short of the button's column so the two never overlap. {@code name} is
     * what the edit screen opens with; {@code hidden} makes it offer Restore instead.
     */
    private int addCreditRow(Component line, boolean own, Section section, String name, boolean hidden,
                             int innerX, int innerW, int y) {
        if (!own) {
            return canvas.addWrappedAt(line, innerX, innerW, y, CardCanvas.COLOUR_DESC);
        }
        DarkTintedButton edit = new DarkTintedButton(innerX + innerW - EDIT_W, 0, EDIT_W, EDIT_H,
                Component.translatable("gui.dungeontrain.credits.translations.edit"),
                b -> Minecraft.getInstance().setScreen(
                        new CreditEditScreen(this, section, name, hidden, this::onEdited)));
        edit.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.credits.rename.title")));
        addWidget(edit);
        editSlots.add(new EditSlot(edit, y));
        return canvas.addWrappedAt(line, innerX, Math.max(1, innerW - EDIT_W - EDIT_GAP), y, CardCanvas.COLOUR_DESC);
    }

    /** "Anonymous", or "Anonymous (you)" for this player's own line. */
    private static Component anonymousName(boolean own) {
        return Component.translatable(own ? "gui.dungeontrain.credits.anonymous_you" : "gui.dungeontrain.credits.anonymous");
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

    /** The "Builders" card: heading, accent bar, the thank-you line, then one line per builder. */
    private int addBuildersCard(List<TemplateBuilderCredits.Builder> builders, int top) {
        int innerX = canvas.colX() + CardCanvas.CARD_PAD;
        int innerW = Math.max(1, canvas.colW() - CardCanvas.CARD_PAD * 2);
        int y = top + CardCanvas.CARD_PAD;

        y = canvas.addWrappedAt(Component.translatable("gui.dungeontrain.credits.builders.header"),
                innerX, innerW, y, CardCanvas.COLOUR_HEADER);
        y += CardCanvas.RULE_GAP;
        y = canvas.addRule(innerX, y, Math.min(CardCanvas.RULE_W, innerW), ACCENT_BUILDERS);
        y += CardCanvas.RULE_TO_BODY;

        y = canvas.addWrappedAt(Component.translatable("gui.dungeontrain.credits.builders.desc"),
                innerX, innerW, y, CardCanvas.COLOUR_DESC);
        y += DESC_GAP;
        // Top five first; See more opens the whole list, ten to a page.
        List<TemplateBuilderCredits.Builder> topFive = builders.subList(0,
                Math.min(builders.size(), CreditsPaging.BUILDERS_COLLAPSED));
        CreditsPaging.View<TemplateBuilderCredits.Builder> view = buildersPaging.view(topFive, builders);
        for (TemplateBuilderCredits.Builder builder : view.rows()) {
            boolean own = !ownUuid.isEmpty() && ownUuid.equals(builder.uuid());
            CreditsSelfEdits.Shown shown = own
                    ? CreditsSelfEdits.apply(Section.BUILDERS, builder.name(), builder.anonymous())
                    : new CreditsSelfEdits.Shown(builder.name(), builder.anonymous());
            Component name = shown.anonymous() ? anonymousName(own)
                    : Component.literal(shown.name().isEmpty() ? builder.display() : shown.name());
            y = addCreditRow(builderLine(builder, name), own, Section.BUILDERS, shown.name(), shown.anonymous(),
                    innerX, innerW, y);
        }
        y = addControls(view, "builders", innerX, innerW, y);

        y += CardCanvas.CARD_PAD;
        canvas.addCard(top, y - top);
        return y;
    }

    /** The "Writers" card: heading, accent bar, the thank-you line, then one line per writer. */
    private int addWritersCard(List<RelayWriters.Writer> writers, int top) {
        int innerX = canvas.colX() + CardCanvas.CARD_PAD;
        int innerW = Math.max(1, canvas.colW() - CardCanvas.CARD_PAD * 2);
        int y = top + CardCanvas.CARD_PAD;

        y = canvas.addWrappedAt(Component.translatable("gui.dungeontrain.credits.writers.header"),
                innerX, innerW, y, CardCanvas.COLOUR_HEADER);
        y += CardCanvas.RULE_GAP;
        y = canvas.addRule(innerX, y, Math.min(CardCanvas.RULE_W, innerW), ACCENT_WRITERS);
        y += CardCanvas.RULE_TO_BODY;

        y = canvas.addWrappedAt(Component.translatable("gui.dungeontrain.credits.writers.desc"),
                innerX, innerW, y, CardCanvas.COLOUR_DESC);
        y += DESC_GAP;
        List<RelayWriters.Writer> topFive = writers.subList(0, Math.min(writers.size(), CreditsPaging.BUILDERS_COLLAPSED));
        CreditsPaging.View<RelayWriters.Writer> view = writersPaging.view(topFive, writers);
        for (RelayWriters.Writer writer : view.rows()) {
            boolean own = writerStanding != null && writer.rank() > 0 && writer.rank() == writerStanding.rank();
            CreditsSelfEdits.Shown shown = own
                    ? CreditsSelfEdits.apply(Section.WRITERS, writer.name(), writer.anonymous())
                    : new CreditsSelfEdits.Shown(writer.name(), writer.anonymous());
            Component name = shown.anonymous() ? anonymousName(own) : Component.literal(shown.name());
            y = addCreditRow(Component.translatable("gui.dungeontrain.credits.writers.person_line",
                    name, Component.literal(Integer.toString(writer.books()))),
                    own, Section.WRITERS, shown.name(), shown.anonymous(), innerX, innerW, y);
        }
        y = addControls(view, "writers", innerX, innerW, y);
        // How to get on the list — only at the very end of it: the last page once See more has
        // been pressed, or straight away when the whole list already fits without one.
        if (view.atEnd()) {
            y += DESC_GAP;
            y = canvas.addWrappedAt(Component.translatable("gui.dungeontrain.credits.writers.how",
                    Component.literal(Integer.toString(RelayWriters.MIN_BOOKS))),
                    innerX, innerW, y, CardCanvas.COLOUR_DESC);
        }

        y += CardCanvas.CARD_PAD;
        canvas.addCard(top, y - top);
        return y;
    }

    /** "&lt;Name&gt; — N templates" (or "1 template"): one line per builder. */
    private static Component builderLine(TemplateBuilderCredits.Builder builder, Component name) {
        String key = builder.templates() == 1
                ? "gui.dungeontrain.credits.builders.person_line_one"
                : "gui.dungeontrain.credits.builders.person_line";
        return Component.translatable(key, name, Component.literal(Integer.toString(builder.templates())));
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
        // Everyone above 1% of a language first; See more opens the whole list, ten to a page.
        List<TranslationContributor> notable = contributors.stream().filter(CreditsScreen::aboveMinShare).toList();
        CreditsPaging.View<TranslationContributor> view = translatorsPaging.view(notable, contributors);
        boolean hiddenSelf = CreditsSelfEdits.get().hidden() && !ownNames.isEmpty();
        for (TranslationContributor contributor : view.rows()) {
            // The Anonymous line is this player's own while they have asked to be on it — that is
            // where their names were folded (see merge above) and where Restore lives.
            boolean own = contributor.isAnonymous() ? hiddenSelf : ownNames.contains(contributor.name());
            String name = contributor.isAnonymous() ? ownNames.iterator().next() : contributor.name();
            y = addCreditRow(personLine(contributor, own), own, Section.TRANSLATIONS, name,
                    contributor.isAnonymous(), innerX, innerW, y);
        }
        y = addControls(view, "translators", innerX, innerW, y);

        y += CardCanvas.CARD_PAD;
        canvas.addCard(top, y - top);
        return y;
    }

    /** True when the contributor's strongest known language share clears the collapsed-list bar. */
    static boolean aboveMinShare(TranslationContributor contributor) {
        for (TranslationContributor.LanguageShare share : contributor.languages()) {
            if (share.total() > 0 && share.fraction() > CreditsPaging.TRANSLATOR_MIN_SHARE) return true;
        }
        return false;
    }

    /**
     * The line under a paged list: Prev · Page n of m · Next when there is more than one page, then
     * <i>See more</i> while collapsed or <i>See less</i> once expanded. Nothing at all when the
     * whole list already fits.
     */
    private int addControls(CreditsPaging.View<?> view, String card, int x, int w, int y) {
        MutableComponent line = Component.empty();
        if (view.pages() > 1) {
            if (view.prev()) line.append(control("gui.dungeontrain.credits.prev", card + "/prev")).append("  ");
            line.append(Component.translatable("gui.dungeontrain.credits.page",
                    Component.literal(Integer.toString(view.page() + 1)),
                    Component.literal(Integer.toString(view.pages()))));
            if (view.next()) line.append("  ").append(control("gui.dungeontrain.credits.next", card + "/next"));
        }
        if (view.seeMore() || view.seeLess()) {
            if (view.pages() > 1) line.append("   ");
            line.append(view.seeMore()
                    ? control("gui.dungeontrain.credits.see_more", card + "/more")
                    : control("gui.dungeontrain.credits.see_less", card + "/less"));
        }
        if (line.getSiblings().isEmpty()) return y;
        y += DESC_GAP;
        return canvas.addWrappedAt(line, x, w, y, CardCanvas.COLOUR_DESC);
    }

    /** A link-styled in-page control; the click is routed by {@link #mouseClicked}, never run as a command. */
    private static Component control(String key, String action) {
        return Component.translatable(key).withStyle(s -> s
                .withColor(CardCanvas.COLOUR_LINK)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, CONTROL_PREFIX + action)));
    }

    /** A See more / Prev / Next press: move that card's paging and re-lay the page in place. */
    private void onControl(String action) {
        String[] parts = action.split("/");
        if (parts.length != 2) return;
        CreditsPaging paging = switch (parts[0]) {
            case "builders" -> buildersPaging;
            case "writers" -> writersPaging;
            default -> translatorsPaging;
        };
        CreditsPaging next = switch (parts[1]) {
            case "more" -> paging.expand();
            case "less" -> paging.collapse();
            case "next" -> paging.nextPage();
            case "prev" -> paging.prevPage();
            default -> paging;
        };
        switch (parts[0]) {
            case "builders" -> buildersPaging = next;
            case "writers" -> writersPaging = next;
            default -> translatorsPaging = next;
        }
        rebuildWidgets();
    }

    /**
     * "&lt;Name&gt; — &lt;Language&gt; (P%), &lt;Language&gt; (P%)": one line per contributor, their
     * languages in the generated (strongest-share-first) order. The name links when the
     * contributor has a URL.
     */
    private Component personLine(TranslationContributor contributor, boolean own) {
        Component nameComp = contributor.isAnonymous() ? anonymousName(own) : contributor.url()
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
            if (style != null && style.getClickEvent() != null
                    && style.getClickEvent().getAction() == ClickEvent.Action.RUN_COMMAND
                    && style.getClickEvent().getValue().startsWith(CONTROL_PREFIX)) {
                onControl(style.getClickEvent().getValue().substring(CONTROL_PREFIX.length()));
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
        renderEditButtons(g, mouseX, mouseY, partialTick);
    }

    /**
     * The Edit buttons, placed against their lines at the current scroll and clipped to the
     * viewport like the canvas's own content. A button scrolled out of view is also hidden so it
     * cannot be clicked through the button row or the title.
     */
    private void renderEditButtons(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (editSlots.isEmpty()) {
            return;
        }
        int top = canvas.viewportTop();
        int bottom = canvas.viewportBottom();
        g.enableScissor(canvas.colX() - CardCanvas.PANEL_PAD, top,
                canvas.colX() + canvas.colW() + CardCanvas.PANEL_PAD, bottom);
        for (EditSlot slot : editSlots) {
            // Centred on the 9px text line: the button is 14 tall, so it starts 2-3px above it.
            int y = canvas.screenY(slot.canvasY()) - (EDIT_H - canvas.lineHeight()) / 2;
            slot.button().setY(y);
            boolean visible = y + EDIT_H > top && y < bottom;
            slot.button().visible = visible;
            if (visible) {
                slot.button().render(g, mouseX, mouseY, partialTick);
            }
        }
        g.disableScissor();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
