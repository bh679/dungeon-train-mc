package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.relay.BuilderReviewEdits;
import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.net.BuilderReviewPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The reviewer's comment box: one verdict already chosen (Accept, Feedback or Decline), one field for
 * what the build's author should read beside it, Cancel and Save.
 *
 * <p>A trimmed {@link BuilderSubmitNoteScreen}: one box, no pages, no block hints. Pre-filled with the
 * comment already on the build, so re-deciding keeps it unless the reviewer rewrites it. Nothing is
 * sent until Save — Cancel returns to the editor with the build untouched. Save flips the tile at once
 * ({@link BuilderProfileState#noteReview}) and sends the verdict; the server answers in chat.</p>
 */
public final class BuilderReviewCommentScreen extends Screen {

    private static final int FIELD_WIDTH = 300;
    private static final int BOX_HEIGHT = 90;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 6;
    private static final int HEADER_HEIGHT = 50;
    private static final int COUNTER_GAP = 14;
    private static final int BOX_PADDING = 4;
    private static final int TEXT_COLOUR = 0xFFFFFFFF;
    private static final int HINT_COLOUR = 0xFFA0A0A0;
    private static final int PLACEHOLDER_COLOUR = 0xFF707070;

    private final Screen backScreen;
    private final BuilderProfilePacket.Entry entry;
    private final boolean live;
    private final String review;
    private String comment;
    private MultiLineEditBox box;
    private int top;

    private BuilderReviewCommentScreen(Screen backScreen, BuilderProfilePacket.Entry entry, boolean live, String review) {
        super(Component.translatable(titleKey(review)));
        this.backScreen = backScreen;
        this.entry = entry;
        this.live = live;
        this.review = review;
        this.comment = entry.reviewComment();
    }

    /** The screen's title names the verdict, so a wrong press is caught before anything is typed. */
    static String titleKey(String review) {
        return switch (BuilderReviewState.of(review)) {
            case BuilderReviewState.ACCEPTED -> "gui.dungeontrain.builder.profile.review.accept_title";
            case BuilderReviewState.FEEDBACK -> "gui.dungeontrain.builder.profile.review.feedback_title";
            default -> "gui.dungeontrain.builder.profile.review.decline_title";
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
        int content = HEADER_HEIGHT + BOX_HEIGHT + COUNTER_GAP + ROW_HEIGHT + ROW_GAP;
        this.top = Math.max(8, (this.height - content) / 2);
        int y = top + HEADER_HEIGHT;
        this.box = new MultiLineEditBox(this.font, x, y, fieldWidth, BOX_HEIGHT,
                Component.translatable("gui.dungeontrain.builder.profile.review.comment_placeholder"),
                Component.translatable("gui.dungeontrain.builder.profile.review.comment_title"));
        box.setCharacterLimit(BuilderReviewEdits.COMMENT_MAX);
        box.setValue(comment);
        box.setValueListener(value -> comment = value);
        addRenderableWidget(box);
        setInitialFocus(box);
        y += BOX_HEIGHT + COUNTER_GAP;
        int half = (fieldWidth - ROW_GAP) / 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x, y, half, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.builder.profile.review.save"),
                        b -> save())
                .bounds(x + fieldWidth - half, y, half, ROW_HEIGHT).build());
    }

    private void save() {
        String written = comment == null ? "" : comment.strip();
        // The picture rides only with an accept — it is for the announcement, and only an accept makes one.
        byte[] render = BuilderReviewState.ACCEPTED.equals(review) ? BuildRenderCapture.png(entry) : null;
        BuilderProfileState.noteReview(entry.relayId(), review, written);
        DungeonTrainNet.sendToServer(new BuilderReviewPacket(entry.relayId(), entry.ownerUuid(), entry.ownerName(),
                entry.buildName(), entry.kind(), entry.subKind(), live, review, written, render));
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
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(backScreen);
    }
}
