package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The title-screen slot button that is normally <b>Train Editor</b> and, on a dev build while
 * Shift is held, becomes <b>Train Builder</b>.
 *
 * <p>The Editor is the default because it is the finished tool. The Builder is still being built,
 * so it sits behind Shift — and because the reveal also requires a dev build, a release jar never
 * offers it at all. That is the point: an unfinished tool should not be the first thing a player
 * meets on the title screen.</p>
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
        super(x, y, width, height, EDITOR_LABEL, b -> {});
        this.openBuilder = openBuilder;
        this.openEditor = openEditor;
    }

    /**
     * Whether the Train Builder is revealed instead of the editor.
     *
     * <p>Pure and package-visible for tests: {@code branch} is {@code VersionInfo.BRANCH}, baked
     * at build time, and only the literal {@code main} ref counts as a release build. A null or
     * {@code "?"} branch (git detection failed) is treated as a dev build — the same
     * fail-open direction {@code EditorMenuScreen.shouldShowDevModeToggle} takes, since hiding a
     * developer affordance from a developer is the worse error.</p>
     *
     * <p>Because a release build never reveals, a {@code main} jar offers only the Train Editor
     * from this slot. The Builder is reachable there by no other route — which is deliberate while
     * it is unfinished, and is the thing to revisit when it ships.</p>
     */
    public static boolean shouldRevealBuilder(String branch, boolean shiftDown) {
        return shiftDown && !"main".equals(branch);
    }

    private boolean builderRevealed() {
        return shouldRevealBuilder(games.brennan.dungeontrain.client.VersionInfo.BRANCH, Screen.hasShiftDown());
    }

    @Override
    public void onPress() {
        if (builderRevealed()) {
            openBuilder.run();
        } else {
            openEditor.run();
        }
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.setMessage(builderRevealed() ? BUILDER_LABEL : EDITOR_LABEL);
        super.renderWidget(g, mouseX, mouseY, partialTick);
    }
}
