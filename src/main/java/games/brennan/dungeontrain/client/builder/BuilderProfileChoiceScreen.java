package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.builder.relay.BuilderRelayInstall;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Shared shape for the name-collision questions: a wrapped title, a wrapped note, then a column of
 * answers and a cancel.
 *
 * <p>The text wraps because these screens are read at GUI Scale 3 as often as at 2, and a sentence
 * drawn with {@code drawCenteredString} simply runs off both edges there — the player loses the
 * half of the explanation that tells them which copy stays linked to their profile. The text is
 * laid out <i>upwards</i> from the button column rather than downwards from a fixed top, so a note
 * that wraps to three lines pushes itself clear of the buttons instead of into them.</p>
 */
@OnlyIn(Dist.CLIENT)
abstract class BuilderProfileChoiceScreen extends Screen {

    protected static final int BUTTON_WIDTH = 220;
    /** Half of a row, less the gap between the two halves, so a pair fills the same column width. */
    protected static final int HALF_WIDTH = (BUTTON_WIDTH - 4) / 2;
    protected static final int BUTTON_HEIGHT = 20;
    protected static final int BUTTON_GAP = 4;
    private static final int LINE_HEIGHT = 11;
    private static final int TEXT_GAP = 6;
    private static final int NOTE_COLOUR = 0xA0A0A0;

    protected final Screen lastScreen;
    protected final String buildName;
    /** Names this install will not write over, as the answer that opened this screen reported them. */
    protected Set<String> takenNames = Set.of();

    /** Given a resolution and the name the player chose (empty when none was needed), do the download. */
    protected final BiConsumer<BuilderRelayInstall.Resolution, String> onChosen;

    private List<FormattedCharSequence> titleLines = List.of();
    private List<FormattedCharSequence> hintLines = List.of();
    private int buttonsTop;
    private int nextY;

    protected BuilderProfileChoiceScreen(Component title, Screen lastScreen, String buildName,
                                         BiConsumer<BuilderRelayInstall.Resolution, String> onChosen) {
        super(title);
        this.lastScreen = lastScreen;
        this.buildName = buildName;
        this.onChosen = onChosen;
    }

    /** The note under the title, explaining what the answers do to the build already here. */
    protected abstract Component hint();

    /** Add the answers, in order, with {@link #addChoice} or {@link #addChoiceRow}. */
    protected abstract void addChoices();

    /**
     * The label on the way out. Cancel on the first question, since leaving it abandons the load;
     * Back on anything reached from it, where leaving only undoes the last answer.
     */
    protected Component leaveLabel() {
        return CommonComponents.GUI_CANCEL;
    }

    @Override
    protected void init() {
        int wrapWidth = Math.min(this.width - 40, 320);
        this.titleLines = this.font.split(getTitle(), wrapWidth);
        this.hintLines = this.font.split(hint(), wrapWidth);

        this.buttonsTop = this.height / 2 - 30;
        this.nextY = this.buttonsTop;
        addChoices();

        this.nextY += BUTTON_GAP * 2;
        addRenderableWidget(Button.builder(leaveLabel(), b -> onClose())
                .bounds(left(), this.nextY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    protected void addChoice(Component label, Runnable action) {
        addRenderableWidget(Button.builder(label, b -> action.run())
                .bounds(left(), this.nextY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        this.nextY += BUTTON_HEIGHT + BUTTON_GAP;
    }

    /** Two answers side by side, for a pair that is one either/or rather than a list. */
    protected void addChoiceRow(Component leftLabel, Runnable leftAction,
                                Component rightLabel, Runnable rightAction) {
        addRenderableWidget(Button.builder(leftLabel, b -> leftAction.run())
                .bounds(left(), this.nextY, HALF_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(rightLabel, b -> rightAction.run())
                .bounds(left() + BUTTON_WIDTH - HALF_WIDTH, this.nextY, HALF_WIDTH, BUTTON_HEIGHT)
                .build());
        this.nextY += BUTTON_HEIGHT + BUTTON_GAP;
    }

    private int left() {
        return this.width / 2 - BUTTON_WIDTH / 2;
    }

    /** The build name as the player sees it elsewhere in the builder. */
    protected Component prettyName() {
        return Component.literal(BuilderLabels.pretty(buildName));
    }

    /** What the answer that opened this screen said is already in use, for the name prompt below. */
    void setTakenNames(Set<String> names) {
        this.takenNames = names == null ? Set.of() : names;
    }

    /**
     * Ask for a name, then resolve with it. Both naming answers go through the same prompt; only the
     * question differs, and it matters which build the player thinks they are naming.
     *
     * <p>The box opens on the first free number rather than always on {@code _2} — offering a name
     * that is already taken is offering the player a wasted press.</p>
     */
    protected void promptFor(BuilderRelayInstall.Resolution resolution, String promptKey) {
        this.minecraft.setScreen(new BuilderProfileNameScreen(lastScreen, this,
                Component.translatable(promptKey, prettyName()),
                BuilderNewOptions.firstFreeName(buildName, takenNames), takenNames, false,
                chosen -> onChosen.accept(resolution, chosen)));
    }

    /** Resolve with no name needed, and hand the player back to where they came from. */
    protected void choose(BuilderRelayInstall.Resolution resolution) {
        this.minecraft.setScreen(lastScreen);
        onChosen.accept(resolution, "");
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int hintTop = this.buttonsTop - TEXT_GAP - hintLines.size() * LINE_HEIGHT;
        drawLines(g, hintLines, hintTop, NOTE_COLOUR);
        drawLines(g, titleLines, hintTop - TEXT_GAP - titleLines.size() * LINE_HEIGHT, 0xFFFFFF);
    }

    private void drawLines(GuiGraphics g, List<FormattedCharSequence> lines, int top, int colour) {
        int y = top;
        for (FormattedCharSequence line : lines) {
            g.drawString(this.font, line, this.width / 2 - this.font.width(line) / 2, y, colour);
            y += LINE_HEIGHT;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(lastScreen);
    }
}
