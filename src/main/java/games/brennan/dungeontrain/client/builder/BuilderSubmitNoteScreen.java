package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.relay.SubmitNote;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorScreenLang;
import games.brennan.dungeontrain.editor.SubmitHints;
import games.brennan.dungeontrain.net.BuilderProfileActionPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
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
 * <p>Up to three questions, one page each: <b>Redstone</b> when the build has machine parts,
 * <b>Loot</b> when it has loot or valuable blocks (both decided on the server from the build's blocks —
 * {@link SubmitHints}), and always, last, the general question. One at a time because three stacked
 * boxes do not fit a normal window at a readable size; Continue and Back step between them, and what
 * was typed on a page is kept when leaving it. With only the general question there is one page, no
 * label and no step count — it looks as it did before there were others.</p>
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
    /** Height of a box's label line above it — room for the label and its row of block icons. */
    private static final int LABEL_HEIGHT = 15;
    /** Size a found block's icon is drawn at beside its label. */
    private static final int ICON = 12;
    private static final int ICON_GAP = 4;
    /** Room under a box for its own {@code n/1000} counter, which it draws 4px below itself. */
    private static final int COUNTER_GAP = 14;
    /** Space above the first box for the progress bar, the title and the prompt. */
    private static final int HEADER_HEIGHT = 50;
    /** The progress bar across the top: its height, and the gap between its segments. */
    private static final int BAR_HEIGHT = 4;
    private static final int BAR_GAP = 3;
    private static final int BAR_EMPTY = 0xFF3A3A3A;
    private static final int BAR_ANSWERED = 0xFF5FBF5F;
    private static final int BAR_CURRENT = 0xFFFFFFFF;
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
    private final SubmitHints.Hints hints;
    private final Consumer<SubmitNote> onSubmit;

    /** Kept across pages and resizes, so neither empties a box. */
    private final Map<Question, String> answers = new EnumMap<>(Question.class);
    /** Which question is showing — an index into {@link #questions}. */
    private int page;
    private MultiLineEditBox box;
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
        this.hints = h;
        List<Question> asked = new ArrayList<>();
        if (h.hasRedstone()) asked.add(Question.REDSTONE);
        if (h.hasLoot()) asked.add(Question.LOOT);
        asked.add(Question.NOTES);
        this.questions = List.copyOf(asked);
        for (Question q : questions) answers.put(q, "");
    }

    private boolean labelled() {
        return questions.size() > 1;
    }

    private Question current() {
        return questions.get(page);
    }

    private boolean lastPage() {
        return page == questions.size() - 1;
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(FIELD_WIDTH, this.width - 32);
        int x = this.width / 2 - fieldWidth / 2;
        int label = labelled() ? LABEL_HEIGHT : 0;

        // One question per page; the box shrinks only on a window too short even for that.
        int fixed = HEADER_HEIGHT + label + COUNTER_GAP + ROW_HEIGHT + ROW_GAP + LABEL_HEIGHT + 16;
        this.boxHeight = Math.max(MIN_BOX_HEIGHT, Math.min(MAX_BOX_HEIGHT, this.height - fixed));
        int content = HEADER_HEIGHT + label + boxHeight + COUNTER_GAP + ROW_HEIGHT + ROW_GAP + LABEL_HEIGHT;
        this.top = Math.max(8, (this.height - content) / 2);

        Question q = current();
        int y = top + HEADER_HEIGHT + label;
        this.box = new MultiLineEditBox(this.font, x, y, fieldWidth, boxHeight, q.placeholder(),
                labelled() ? q.label() : Component.translatable("gui.dungeontrain.builder.profile.note.label"));
        box.setCharacterLimit(BuilderProfileActionPacket.NOTE_MAX);
        box.setValue(answers.getOrDefault(q, ""));
        box.setValueListener(value -> answers.put(q, value));
        addRenderableWidget(box);
        setInitialFocus(box);
        y += boxHeight + COUNTER_GAP;

        // Left steps back (or cancels on the first page); right steps on (or submits on the last).
        int half = (fieldWidth - ROW_GAP) / 2;
        addRenderableWidget(Button.builder(page == 0 ? CommonComponents.GUI_CANCEL : CommonComponents.GUI_BACK,
                        b -> { if (page == 0) onClose(); else turn(-1); })
                .bounds(x, y, half, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(lastPage()
                                ? Component.translatable("gui.dungeontrain.builder.profile.note.submit")
                                : CommonComponents.GUI_CONTINUE,
                        b -> { if (lastPage()) submit(); else turn(1); })
                .bounds(x + fieldWidth - half, y, half, ROW_HEIGHT).build());
    }

    /** Show the question {@code step} pages away, keeping what was typed on this one. */
    private void turn(int step) {
        this.page = Math.max(0, Math.min(questions.size() - 1, page + step));
        rebuildWidgets();
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
        if (labelled()) renderProgress(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, top + 14, TEXT_COLOUR);
        g.drawCenteredString(this.font,
                Component.translatable("gui.dungeontrain.builder.profile.note.prompt", buildName),
                this.width / 2, top + 28, HINT_COLOUR);
        List<Component> tooltip = null;
        Question q = current();
        if (box != null) {
            if (labelled()) {
                int labelY = box.getY() - LABEL_HEIGHT;
                g.drawString(this.font, q.label(), box.getX(), labelY + 4, TEXT_COLOUR);
                String step = (page + 1) + "/" + questions.size();
                int stepX = box.getX() + box.getWidth() - this.font.width(step);
                g.drawString(this.font, step, stepX, labelY + 4, HINT_COLOUR);
                tooltip = renderFound(g, found(q), box.getX() + this.font.width(q.label()) + 6,
                        labelY + 1, stepX - ICON_GAP, mouseX, mouseY);
            }
            renderPlaceholder(g, q, box);
        }
        if (tooltip != null) g.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
        int footY = top + HEADER_HEIGHT + (labelled() ? LABEL_HEIGHT : 0) + boxHeight + COUNTER_GAP
                + ROW_HEIGHT + ROW_GAP;
        g.drawCenteredString(this.font,
                Component.translatable("gui.dungeontrain.builder.profile.note.optional"),
                this.width / 2, footY, HINT_COLOUR);
    }

    /**
     * One segment per question across the top, the width of the box: filled once that question has
     * an answer, outlined while it is the one showing — how far through the note the author is, at a
     * glance, without counting pages.
     */
    private void renderProgress(GuiGraphics g) {
        if (box == null) return;
        int n = questions.size();
        int x = box.getX();
        int width = (box.getWidth() - BAR_GAP * (n - 1)) / n;
        int y = top;
        for (int i = 0; i < n; i++) {
            int left = x + i * (width + BAR_GAP);
            boolean answered = !answers.getOrDefault(questions.get(i), "").isBlank();
            if (i == page) g.fill(left - 1, y - 1, left + width + 1, y + BAR_HEIGHT + 1, BAR_CURRENT);
            g.fill(left, y, left + width, y + BAR_HEIGHT, answered ? BAR_ANSWERED : BAR_EMPTY);
        }
    }

    /** The blocks that earned a question — none for the general one. */
    private List<SubmitHints.Found> found(Question q) {
        return switch (q) {
            case REDSTONE -> hints.redstone();
            case LOOT -> hints.loot();
            case NOTES -> List.of();
        };
    }

    /**
     * The blocks behind a question, as a row of icons after its label, in the order the server ranked
     * them (most valuable first). What does not fit before {@code right} is counted as "+N". Returns
     * the tooltip of the icon under the mouse, or null.
     */
    private List<Component> renderFound(GuiGraphics g, List<SubmitHints.Found> found, int x, int y, int right,
                                        int mouseX, int mouseY) {
        List<Component> tooltip = null;
        for (int i = 0; i < found.size(); i++) {
            SubmitHints.Found f = found.get(i);
            String count = f.count() > 1 ? "×" + f.count() : "";
            int width = ICON + (count.isEmpty() ? 0 : 1 + this.font.width(count));
            int more = found.size() - i - 1;
            int reserve = more > 0 ? ICON_GAP + this.font.width("+" + more) : 0;
            if (x + width + reserve > right) {
                g.drawString(this.font, "+" + (found.size() - i), x, y + 3, HINT_COLOUR);
                break;
            }
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(ICON / 16f, ICON / 16f, 1f);
            g.renderItem(new ItemStack(f.block()), 0, 0);
            g.pose().popPose();
            if (!count.isEmpty()) g.drawString(this.font, count, x + ICON + 1, y + 3, HINT_COLOUR);
            if (mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + ICON) tooltip = tooltipOf(f);
            x += width + ICON_GAP;
        }
        return tooltip;
    }

    /** A found block's name and count, and where its loot comes from. */
    private static List<Component> tooltipOf(SubmitHints.Found f) {
        List<Component> lines = new ArrayList<>(2);
        Component name = f.block().getName();
        lines.add(f.count() > 1 ? name.copy().append(" ×" + f.count()) : name);
        String source = switch (f.kind()) {
            case REDSTONE -> null;
            case VALUABLE -> Component.translatable("gui.dungeontrain.builder.profile.note.valuable").getString();
            case POOL -> EditorScreenLang.text(EditorScreenLang.SHEET_LOOT_POOL);
            case PREFAB -> EditorScreenLang.text(EditorScreenLang.SHEET_LOOT_PREFAB, f.detail());
            case INLINE -> EditorScreenLang.text(EditorScreenLang.SHEET_LOOT_INLINE);
            case TABLE -> EditorScreenLang.text(EditorScreenLang.SHEET_LOOT_TABLE, f.detail());
        };
        if (source != null) lines.add(Component.literal(source).withStyle(ChatFormatting.GRAY));
        return lines;
    }

    /**
     * Vanilla only draws an edit box's placeholder while the box is <em>unfocused</em>, and this screen
     * focuses each page's box on open — so without this its question never shows. Drawn whenever the
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
