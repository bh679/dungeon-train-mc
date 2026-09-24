package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.relay.SubmitNote;
import games.brennan.dungeontrain.editor.SubmitHints;
import games.brennan.dungeontrain.net.BuilderProfileActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The questions asked between pressing <b>Submit for Review</b> and the submission going out.
 *
 * <p>A build's blocks say what it is, not how it is meant to be used. Redstone that only makes sense
 * once you know which lever to pull, loot that is there on purpose rather than by accident, a room
 * that wants to sit near the engine — none of that is in the NBT, and a reviewer who has to guess
 * will guess wrong some of the time. So the author is asked, and whatever they write rides along with
 * the publish call as the build's note.</p>
 *
 * <p>Up to three boxes, stacked: <b>Redstone</b> when the build has machine parts, <b>Loot</b> when it
 * has loot or valuable blocks (both decided on the server from the build's blocks — {@link SubmitHints}),
 * and always, last, the general question. With only the general one there is nothing to tell apart,
 * so the boxes are labelled only when there is more than one.</p>
 *
 * <p>Every box is optional: all empty and Submit is a submission like any other. Cancel returns to
 * the screen that asked without sending anything — the press is only committed by Submit here, which
 * is why the callers hand this screen the send rather than sending first and asking after.</p>
 *
 * <p>Each box's cap matches {@link BuilderProfileActionPacket#NOTE_MAX}, so what a box will hold is
 * what the packet will carry; the server trims again regardless.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderSubmitNoteScreen extends Screen {

    private static final int FIELD_WIDTH = 300;
    private static final int MAX_BOX_HEIGHT = 90;
    private static final int MIN_BOX_HEIGHT = 36;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 6;
    /** Height of a box's label line above it. */
    private static final int LABEL_HEIGHT = 12;
    /** Room under a box for its own {@code n/1000} counter, which it draws 4px below itself. */
    private static final int COUNTER_GAP = 14;
    /** Space above the first box for the title and the prompt. */
    private static final int HEADER_HEIGHT = 40;
    /** Inset of the edit box's text from its border — vanilla's {@code innerPadding()}. */
    private static final int BOX_PADDING = 4;
    private static final int TEXT_COLOUR = 0xFFFFFFFF;
    private static final int HINT_COLOUR = 0xFFA0A0A0;
    private static final int PLACEHOLDER_COLOUR = 0xFF707070;

    /** One question the screen can ask, in the order they stack. */
    private enum Question {
        REDSTONE("redstone"),
        LOOT("loot"),
        NOTES("notes");

        private final String key;

        Question(String key) {
            this.key = key;
        }

        Component label() {
            return Component.translatable("gui.dungeontrain.builder.profile.note." + key + ".label");
        }

        Component placeholder() {
            // The general box keeps the key it has always had.
            return Component.translatable(this == NOTES
                    ? "gui.dungeontrain.builder.profile.note.hint"
                    : "gui.dungeontrain.builder.profile.note." + key + ".hint");
        }
    }

    private final Screen backScreen;
    private final Component buildName;
    private final List<Question> questions;
    private final Consumer<SubmitNote> onSubmit;

    private final Map<Question, MultiLineEditBox> boxes = new EnumMap<>(Question.class);
    /** Kept across resizes, so a window drag mid-sentence does not empty a box. */
    private final Map<Question, String> answers = new EnumMap<>(Question.class);
    private int boxHeight = MAX_BOX_HEIGHT;
    private int top;

    /**
     * @param backScreen the screen to return to, on Submit and on Cancel alike
     * @param buildName  what is being submitted, named in the prompt so the author knows which build
     *                   they are describing
     * @param hints      which extra questions the build earns; {@link SubmitHints.Hints#NONE} asks
     *                   only the general one
     * @param onSubmit   given the note (possibly empty) once Submit is pressed — the send itself
     */
    public BuilderSubmitNoteScreen(Screen backScreen, Component buildName, SubmitHints.Hints hints,
                                   Consumer<SubmitNote> onSubmit) {
        super(Component.translatable("gui.dungeontrain.builder.profile.note.title"));
        this.backScreen = backScreen;
        this.buildName = buildName == null ? Component.empty() : buildName;
        this.onSubmit = onSubmit;
        SubmitHints.Hints h = hints == null ? SubmitHints.Hints.NONE : hints;
        List<Question> asked = new ArrayList<>();
        if (h.redstone()) asked.add(Question.REDSTONE);
        if (h.loot()) asked.add(Question.LOOT);
        asked.add(Question.NOTES);
        this.questions = List.copyOf(asked);
        for (Question q : questions) answers.put(q, "");
    }

    private boolean labelled() {
        return questions.size() > 1;
    }

    /** Height of one question's slot: its label (when shown), its box, and its counter. */
    private int slotHeight() {
        return (labelled() ? LABEL_HEIGHT : 0) + boxHeight + COUNTER_GAP;
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(FIELD_WIDTH, this.width - 32);
        int x = this.width / 2 - fieldWidth / 2;

        // Shrink the boxes until every question, the buttons and the footnote fit the window.
        int n = questions.size();
        int fixed = HEADER_HEIGHT + n * ((labelled() ? LABEL_HEIGHT : 0) + COUNTER_GAP)
                + ROW_HEIGHT + ROW_GAP + LABEL_HEIGHT + 16;
        this.boxHeight = Math.max(MIN_BOX_HEIGHT, Math.min(MAX_BOX_HEIGHT, (this.height - fixed) / n));
        int content = HEADER_HEIGHT + n * slotHeight() + ROW_HEIGHT + ROW_GAP + LABEL_HEIGHT;
        this.top = Math.max(8, (this.height - content) / 2);

        boxes.clear();
        int y = top + HEADER_HEIGHT;
        for (Question q : questions) {
            if (labelled()) y += LABEL_HEIGHT;
            MultiLineEditBox box = new MultiLineEditBox(this.font, x, y, fieldWidth, boxHeight,
                    q.placeholder(), labelled() ? q.label()
                            : Component.translatable("gui.dungeontrain.builder.profile.note.label"));
            box.setCharacterLimit(BuilderProfileActionPacket.NOTE_MAX);
            box.setValue(answers.getOrDefault(q, ""));
            box.setValueListener(value -> answers.put(q, value));
            addRenderableWidget(box);
            boxes.put(q, box);
            y += boxHeight + COUNTER_GAP;
        }
        setInitialFocus(boxes.get(questions.get(0)));

        int half = (fieldWidth - ROW_GAP) / 2;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.dungeontrain.builder.profile.note.submit"), b -> submit())
                .bounds(x, y, half, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x + fieldWidth - half, y, half, ROW_HEIGHT).build());
    }

    private void submit() {
        SubmitNote written = new SubmitNote(answers.getOrDefault(Question.REDSTONE, ""),
                answers.getOrDefault(Question.LOOT, ""), answers.getOrDefault(Question.NOTES, ""));
        this.minecraft.setScreen(backScreen);
        onSubmit.accept(written);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, top + 4, TEXT_COLOUR);
        g.drawCenteredString(this.font,
                Component.translatable("gui.dungeontrain.builder.profile.note.prompt", buildName),
                this.width / 2, top + 18, HINT_COLOUR);
        for (Question q : questions) {
            MultiLineEditBox box = boxes.get(q);
            if (box == null) continue;
            if (labelled()) {
                g.drawString(this.font, q.label(), box.getX(), box.getY() - LABEL_HEIGHT + 2, TEXT_COLOUR);
            }
            renderPlaceholder(g, q, box);
        }
        int footY = top + HEADER_HEIGHT + questions.size() * slotHeight() + ROW_HEIGHT + ROW_GAP;
        g.drawCenteredString(this.font,
                Component.translatable("gui.dungeontrain.builder.profile.note.optional"),
                this.width / 2, footY, HINT_COLOUR);
    }

    /**
     * Vanilla only draws an edit box's placeholder while the box is <em>unfocused</em>, and this screen
     * focuses the first box on open — so without this its question never shows. Drawn whenever the
     * box is empty.
     */
    private void renderPlaceholder(GuiGraphics g, Question q, MultiLineEditBox box) {
        if (!answers.getOrDefault(q, "").isEmpty()) return;
        g.drawWordWrap(this.font, q.placeholder(), box.getX() + BOX_PADDING, box.getY() + BOX_PADDING,
                box.getWidth() - BOX_PADDING * 2, PLACEHOLDER_COLOUR);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(backScreen);
    }

    /**
     * Ask the server which questions {@code relayId} earns, then open over the current screen and
     * return to it afterwards — the path every Submit for Review press takes.
     */
    public static void open(int relayId, Component buildName, Consumer<SubmitNote> onSubmit) {
        BuilderSubmitHintsRequests.ask(relayId, hints -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new BuilderSubmitNoteScreen(mc.screen, buildName, hints, onSubmit));
        });
    }
}
