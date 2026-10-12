package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.relay.BuilderReviewEdits;
import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.client.version.SemverCompare;
import games.brennan.dungeontrain.client.version.VersionCheckState;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.net.BuilderReviewPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The reviewer's comment box: one verdict already chosen (Accept, Feedback, Resubmit or Decline), one
 * field for what the build's author should read beside it, Cancel and Save.
 *
 * <p>A trimmed {@link BuilderSubmitNoteScreen}: one box, no pages, no block hints. Pre-filled with the
 * comment already on the build, so re-deciding keeps it unless the reviewer rewrites it. Nothing is
 * sent until Save — Cancel returns to the editor with the build untouched. Save flips the tile at once
 * ({@link BuilderProfileState#noteReview}) and sends the verdict; the server answers in chat.</p>
 *
 * <p>A <b>Resubmit</b> verdict adds its rule above the box: which Dungeon Train version the author must
 * come back on — the latest release (default), this build's version, or one typed in — and whether that
 * reads as exactly, or above, or below. The relay holds the build until a submit from such a client.</p>
 */
public final class BuilderReviewCommentScreen extends Screen {

    private static final int FIELD_WIDTH = 300;
    private static final int BOX_HEIGHT = 80;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 6;
    private static final int HEADER_HEIGHT = 50;
    private static final int COUNTER_GAP = 14;
    private static final int BOX_PADDING = 4;
    private static final int TEXT_COLOUR = 0xFFFFFFFF;
    private static final int HINT_COLOUR = 0xFFA0A0A0;
    private static final int PLACEHOLDER_COLOUR = 0xFF707070;

    /** Where a resubmit rule's version comes from. */
    enum Source { LATEST, THIS_BUILD, CUSTOM }

    private final Screen backScreen;
    private final BuilderProfilePacket.Entry entry;
    private final boolean live;
    private final String review;
    private final boolean resubmit;
    private String comment;
    private Source source = Source.LATEST;
    private String customVersion = "";
    private String op = BuilderReviewState.OP_GTE;
    private MultiLineEditBox box;
    private EditBox versionField;
    private int top;

    private BuilderReviewCommentScreen(Screen backScreen, BuilderProfilePacket.Entry entry, boolean live, String review) {
        super(Component.translatable(titleKey(review)));
        this.backScreen = backScreen;
        this.entry = entry;
        this.live = live;
        this.review = review;
        this.resubmit = BuilderReviewState.RESUBMIT.equals(review);
        this.comment = entry.reviewComment();
        // Re-deciding a build that already has a rule starts from that rule.
        if (resubmit && !entry.reviewVersion().isEmpty()) {
            this.op = BuilderReviewState.opOf(entry.reviewVersionOp());
            String v = entry.reviewVersion();
            if (v.equals(latestVersion())) this.source = Source.LATEST;
            else if (v.equals(VersionInfo.VERSION)) this.source = Source.THIS_BUILD;
            else { this.source = Source.CUSTOM; this.customVersion = v; }
        }
    }

    /** The screen's title names the verdict, so a wrong press is caught before anything is typed. */
    static String titleKey(String review) {
        return switch (BuilderReviewState.of(review)) {
            case BuilderReviewState.ACCEPTED -> "gui.dungeontrain.builder.profile.review.accept_title";
            case BuilderReviewState.FEEDBACK -> "gui.dungeontrain.builder.profile.review.feedback_title";
            case BuilderReviewState.RESUBMIT -> "gui.dungeontrain.builder.profile.review.resubmit_title";
            default -> "gui.dungeontrain.builder.profile.review.decline_title";
        };
    }

    /** The newest public release the title screen's check found, or this build when it has not yet. */
    static String latestVersion() {
        String tag = VersionCheckState.latestTag();
        String v = tag == null ? "" : SemverCompare.stripV(tag);
        return v.isEmpty() ? VersionInfo.VERSION : v;
    }

    /** The version the rule will carry, for the chosen source. */
    private String ruleVersion() {
        return switch (source) {
            case LATEST -> latestVersion();
            case THIS_BUILD -> VersionInfo.VERSION;
            case CUSTOM -> customVersion.strip();
        };
    }

    /** Open over the current screen for a verdict on {@code entry}, returning to it afterwards. */
    public static void open(BuilderProfilePacket.Entry entry, boolean live, String review) {
        if (entry == null || !BuilderReviewEdits.isVerdict(review)) return;
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new BuilderReviewCommentScreen(mc.screen, entry, live, review));
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(FIELD_WIDTH, this.width - 32);
        int x = this.width / 2 - fieldWidth / 2;
        int ruleRows = resubmit ? 2 : 0;
        int content = HEADER_HEIGHT + ruleRows * (ROW_HEIGHT + ROW_GAP) + BOX_HEIGHT + COUNTER_GAP + ROW_HEIGHT + ROW_GAP;
        this.top = Math.max(8, (this.height - content) / 2);
        int y = top + HEADER_HEIGHT;
        if (resubmit) {
            // Row 1: where the version comes from, and the version itself (typed only on Custom).
            int half = (fieldWidth - ROW_GAP) / 2;
            addRenderableWidget(CycleButton.<Source>builder(this::sourceLabel)
                    .withValues(Source.values()).withInitialValue(source).displayOnlyValue()
                    .create(x, y, half, ROW_HEIGHT, Component.empty(), (b, v) -> { source = v; syncVersionField(); }));
            this.versionField = new EditBox(this.font, x + half + ROW_GAP, y, half, ROW_HEIGHT,
                    Component.translatable("gui.dungeontrain.builder.profile.review.version_custom"));
            versionField.setMaxLength(40);
            versionField.setResponder(v -> { if (source == Source.CUSTOM) customVersion = v; });
            addRenderableWidget(versionField);
            syncVersionField();
            y += ROW_HEIGHT + ROW_GAP;
            // Row 2: how the version reads.
            List<String> ops = new ArrayList<>(List.of(BuilderReviewState.OP_GTE, BuilderReviewState.OP_EXACT, BuilderReviewState.OP_LTE));
            addRenderableWidget(CycleButton.<String>builder(BuilderReviewCommentScreen::opLabel)
                    .withValues(ops).withInitialValue(op).displayOnlyValue()
                    .create(x, y, fieldWidth, ROW_HEIGHT, Component.empty(), (b, v) -> op = v));
            y += ROW_HEIGHT + ROW_GAP;
        }
        this.box = new MultiLineEditBox(this.font, x, y, fieldWidth, BOX_HEIGHT,
                Component.translatable("gui.dungeontrain.builder.profile.review.comment_placeholder"),
                Component.translatable("gui.dungeontrain.builder.profile.review.comment_title"));
        box.setCharacterLimit(BuilderReviewEdits.COMMENT_MAX);
        box.setValue(comment);
        box.setValueListener(value -> comment = value);
        addRenderableWidget(box);
        if (!resubmit) setInitialFocus(box);
        y += BOX_HEIGHT + COUNTER_GAP;
        int half = (fieldWidth - ROW_GAP) / 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x, y, half, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.builder.profile.review.save"),
                        b -> save())
                .bounds(x + fieldWidth - half, y, half, ROW_HEIGHT).build());
    }

    private Component sourceLabel(Source s) {
        return switch (s) {
            case LATEST -> Component.translatable("gui.dungeontrain.builder.profile.review.version_latest");
            case THIS_BUILD -> Component.translatable("gui.dungeontrain.builder.profile.review.version_this_build");
            case CUSTOM -> Component.translatable("gui.dungeontrain.builder.profile.review.version_custom");
        };
    }

    private static Component opLabel(String op) {
        return switch (BuilderReviewState.opOf(op)) {
            case BuilderReviewState.OP_EXACT -> Component.translatable("gui.dungeontrain.builder.profile.review.op_exact");
            case BuilderReviewState.OP_LTE -> Component.translatable("gui.dungeontrain.builder.profile.review.op_lte");
            default -> Component.translatable("gui.dungeontrain.builder.profile.review.op_gte");
        };
    }

    /** The version field shows the chosen source's version, and is typed into only on Custom. */
    private void syncVersionField() {
        if (versionField == null) return;
        boolean custom = source == Source.CUSTOM;
        versionField.setEditable(custom);
        versionField.setValue(custom ? customVersion : ruleVersion());
        if (custom) setFocused(versionField);
    }

    private void save() {
        String written = comment == null ? "" : comment.strip();
        String version = resubmit ? ruleVersion() : "";
        if (resubmit && !BuilderReviewState.isDottedVersion(version)) {
            // Nothing sent: a resubmit rule without a version is not a verdict. Point at the field.
            source = Source.CUSTOM;
            syncVersionField();
            return;
        }
        // The picture rides only with a verdict the channel hears about — it is for the announcement.
        byte[] render = games.brennan.dungeontrain.discord.BuildReviewReporter.announces(review)
                ? BuildRenderCapture.png(entry) : null;
        BuilderProfileState.noteReview(entry.relayId(), review, written, version, resubmit ? op : "");
        DungeonTrainNet.sendToServer(new BuilderReviewPacket(entry.relayId(), entry.ownerUuid(), entry.ownerName(),
                entry.buildName(), entry.kind(), entry.subKind(), live, review, written, render, version, resubmit ? op : ""));
        this.minecraft.setScreen(backScreen);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, top + 14, TEXT_COLOUR);
        String name = entry.buildName().isEmpty() ? "#" + entry.relayId() : BuilderLabels.pretty(entry.buildName());
        String owner = entry.ownerName().isEmpty() ? name : name + " — " + entry.ownerName();
        g.drawCenteredString(this.font, Component.literal(owner), this.width / 2, top + 28, HINT_COLOUR);
        if (box != null && (comment == null || comment.isEmpty())) {
            // Vanilla draws a box's placeholder only while it is unfocused, and this one opens focused.
            g.drawWordWrap(this.font, Component.translatable("gui.dungeontrain.builder.profile.review.comment_placeholder"),
                    box.getX() + BOX_PADDING, box.getY() + BOX_PADDING, box.getWidth() - BOX_PADDING * 2, PLACEHOLDER_COLOUR);
        }
        if (resubmit && versionField != null) {
            // What the author will be told, in one line, so the two controls read as a sentence.
            Component rule = Component.translatable("gui.dungeontrain.builder.profile.review.resubmit_note",
                    BuilderReviewState.ruleText(ruleVersion(), op).getString());
            g.drawCenteredString(this.font, rule, this.width / 2, top + 40, HINT_COLOUR);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(backScreen);
    }
}
