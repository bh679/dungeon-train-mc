package games.brennan.dungeontrain.client.builder;

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

import java.util.function.Consumer;

/**
 * The question asked between pressing <b>Submit for Review</b> and the submission going out: is there
 * anything the reviewer should know?
 *
 * <p>A build's blocks say what it is, not how it is meant to be used. Redstone that only makes sense
 * once you know which lever to pull, loot that is there on purpose rather than by accident, a room
 * that wants to sit near the engine — none of that is in the NBT, and a reviewer who has to guess
 * will guess wrong some of the time. So the author gets a text area, and whatever they write rides
 * along with the publish call as the build's note.</p>
 *
 * <p>The note is optional: an empty box and Submit is a submission like any other. Cancel returns to
 * the screen that asked without sending anything — the press is only committed by Submit here, which
 * is why the callers hand this screen the send rather than sending first and asking after.</p>
 *
 * <p>The cap matches {@link BuilderProfileActionPacket#NOTE_MAX}, so what the box will hold is what
 * the packet will carry; the server trims again regardless.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderSubmitNoteScreen extends Screen {

    private static final int FIELD_WIDTH = 300;
    private static final int FIELD_HEIGHT = 90;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 6;
    /** Room under the box for the edit box's own {@code n/1000} counter, which it draws 4px below itself. */
    private static final int COUNTER_GAP = 14;
    /** Inset of the edit box's text from its border — vanilla's {@code innerPadding()}. */
    private static final int BOX_PADDING = 4;
    private static final int TEXT_COLOUR = 0xFFFFFFFF;
    private static final int HINT_COLOUR = 0xFFA0A0A0;
    private static final int PLACEHOLDER_COLOUR = 0xFF707070;

    private final Screen backScreen;
    private final Component buildName;
    private final Consumer<String> onSubmit;

    private MultiLineEditBox noteBox;
    /** Kept across resizes, so a window drag mid-sentence does not empty the box. */
    private String note = "";

    /**
     * @param backScreen the screen to return to, on Submit and on Cancel alike
     * @param buildName  what is being submitted, named in the prompt so the author knows which build
     *                   they are describing
     * @param onSubmit   given the note (possibly empty) once Submit is pressed — the send itself
     */
    public BuilderSubmitNoteScreen(Screen backScreen, Component buildName, Consumer<String> onSubmit) {
        super(Component.translatable("gui.dungeontrain.builder.profile.note.title"));
        this.backScreen = backScreen;
        this.buildName = buildName == null ? Component.empty() : buildName;
        this.onSubmit = onSubmit;
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(FIELD_WIDTH, this.width - 32);
        int x = this.width / 2 - fieldWidth / 2;
        int y = this.height / 2 - FIELD_HEIGHT / 2;

        this.noteBox = new MultiLineEditBox(this.font, x, y, fieldWidth, FIELD_HEIGHT,
                Component.translatable("gui.dungeontrain.builder.profile.note.hint"),
                Component.translatable("gui.dungeontrain.builder.profile.note.label"));
        this.noteBox.setCharacterLimit(BuilderProfileActionPacket.NOTE_MAX);
        this.noteBox.setValue(note);
        this.noteBox.setValueListener(value -> this.note = value);
        addRenderableWidget(this.noteBox);
        setInitialFocus(this.noteBox);

        y += FIELD_HEIGHT + COUNTER_GAP;
        int half = (fieldWidth - ROW_GAP) / 2;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.dungeontrain.builder.profile.note.submit"), b -> submit())
                .bounds(x, y, half, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x + fieldWidth - half, y, half, ROW_HEIGHT).build());
    }

    private void submit() {
        String written = this.note;
        this.minecraft.setScreen(backScreen);
        onSubmit.accept(written);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int top = this.height / 2 - FIELD_HEIGHT / 2;
        g.drawCenteredString(this.font, this.title, this.width / 2, top - 36, TEXT_COLOUR);
        g.drawCenteredString(this.font,
                Component.translatable("gui.dungeontrain.builder.profile.note.prompt", buildName),
                this.width / 2, top - 22, HINT_COLOUR);
        g.drawCenteredString(this.font,
                Component.translatable("gui.dungeontrain.builder.profile.note.optional"),
                this.width / 2, top + FIELD_HEIGHT + COUNTER_GAP + ROW_HEIGHT + ROW_GAP, HINT_COLOUR);
        renderPlaceholder(g);
    }

    /**
     * Vanilla only draws the edit box's placeholder while the box is <em>unfocused</em>, and this screen
     * focuses it on open — so without this the questions never show. Drawn whenever the box is empty.
     */
    private void renderPlaceholder(GuiGraphics g) {
        if (this.noteBox == null || !this.note.isEmpty()) return;
        g.drawWordWrap(this.font, Component.translatable("gui.dungeontrain.builder.profile.note.hint"),
                this.noteBox.getX() + BOX_PADDING, this.noteBox.getY() + BOX_PADDING,
                this.noteBox.getWidth() - BOX_PADDING * 2, PLACEHOLDER_COLOUR);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(backScreen);
    }

    /** Open over the current screen, returning to it afterwards. */
    public static void open(Component buildName, Consumer<String> onSubmit) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new BuilderSubmitNoteScreen(mc.screen, buildName, onSubmit));
    }
}
