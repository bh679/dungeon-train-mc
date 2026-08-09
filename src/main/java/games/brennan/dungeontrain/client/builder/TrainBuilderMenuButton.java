package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The title-screen slot button that is normally <b>Train Builder</b> and, on a dev build while
 * Shift is held, becomes <b>Train Editor</b>.
 *
 * <p>One widget rather than two: the slot is only half a button wide (Discord takes the other
 * half), so a third button would not fit, and the swap mirrors the Shift affordance
 * {@code DevQuickWorldHandler} already uses on the row above.</p>
 *
 * <p>The label is refreshed every frame in {@link #renderWidget} because Shift can be pressed
 * and released while the title screen sits idle — no screen re-init fires for a modifier key.
 * The press handler re-reads the state at click time rather than trusting the last rendered
 * label, so a click that lands in the same frame Shift is released still does what the button
 * currently says.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class TrainBuilderMenuButton extends DarkTintedButton {

    private static final Component BUILDER_LABEL = Component.translatable("gui.dungeontrain.builder_button");
    private static final Component EDITOR_LABEL = Component.translatable("gui.dungeontrain.editor_button");

    private final Runnable openBuilder;
    private final Runnable openEditor;

    public TrainBuilderMenuButton(int x, int y, int width, int height,
                                  Runnable openBuilder, Runnable openEditor) {
        super(x, y, width, height, BUILDER_LABEL, b -> {});
        this.openBuilder = openBuilder;
        this.openEditor = openEditor;
    }

    /**
     * Whether the Train Editor is revealed instead of the builder.
     *
     * <p>Pure and package-visible for tests: {@code branch} is {@code VersionInfo.BRANCH}, baked
     * at build time, and only the literal {@code main} ref counts as a release build. A null or
     * {@code "?"} branch (git detection failed) is treated as a dev build — the same
     * fail-open direction {@code EditorMenuScreen.shouldShowDevModeToggle} takes, since hiding a
     * developer affordance from a developer is the worse error.</p>
     */
    public static boolean shouldRevealEditor(String branch, boolean shiftDown) {
        return shiftDown && !"main".equals(branch);
    }

    private boolean editorRevealed() {
        return shouldRevealEditor(games.brennan.dungeontrain.client.VersionInfo.BRANCH, Screen.hasShiftDown());
    }

    @Override
    public void onPress() {
        if (editorRevealed()) {
            openEditor.run();
        } else {
            openBuilder.run();
        }
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.setMessage(editorRevealed() ? EDITOR_LABEL : BUILDER_LABEL);
        super.renderWidget(g, mouseX, mouseY, partialTick);
    }
}
