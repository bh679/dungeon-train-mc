package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * "You have unsaved changes" — asked when a translator presses <b>Next ›</b> with text in the box
 * they have not saved.
 *
 * <p>Three answers, not two, which is why this is here rather than a vanilla {@code ConfirmScreen}:
 * save and go on, throw it away and go on, or stay where you are. A two-button prompt has to fold
 * "stay here" into one of the other answers, and both foldings lose work — the whole reason the
 * prompt exists.</p>
 */
public final class TranslationUnsavedScreen extends Screen {

    private static final int ROW_H = 20;
    private static final int GAP = 8;
    private static final int TEXT_TOP = 60;

    /** What the translator chose. */
    public enum Choice { SAVE, DISCARD, STAY }

    private final java.util.function.Consumer<Choice> onChoice;

    public TranslationUnsavedScreen(java.util.function.Consumer<Choice> onChoice) {
        super(Component.translatable("gui.dungeontrain.translate.group.unsaved"));
        this.onChoice = onChoice;
    }

    @Override
    protected void init() {
        int textWidth = Math.min(width - 40, 320);
        addRenderableWidget(new MultiLineTextWidget(width / 2 - textWidth / 2, TEXT_TOP,
            Component.translatable("gui.dungeontrain.translate.group.unsaved.detail"), font)
            .setMaxWidth(textWidth)
            .setCentered(true));

        // Stacked rather than in a row: the two that continue read as one pair of alternatives, and
        // the way out sits apart from them where a mis-click cannot reach it.
        int buttonWidth = Math.min(width - 40, 220);
        int x = width / 2 - buttonWidth / 2;
        int y = Math.min(height - ROW_H * 3 - GAP * 3, height / 2 + GAP);
        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.group.unsaved.save"),
            b -> choose(Choice.SAVE)).bounds(x, y, buttonWidth, ROW_H).build());
        y += ROW_H + GAP / 2;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.group.unsaved.discard"),
            b -> choose(Choice.DISCARD)).bounds(x, y, buttonWidth, ROW_H).build());
        y += ROW_H + GAP;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> choose(Choice.STAY))
            .bounds(x, y, buttonWidth, ROW_H).build());
    }

    private void choose(Choice choice) {
        onChoice.accept(choice);
    }

    /** Escape keeps the work: the only answer that cannot lose anything. */
    @Override
    public void onClose() {
        choose(Choice.STAY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 30, 0xFFFFFFFF);
    }
}
