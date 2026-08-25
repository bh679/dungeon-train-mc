package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The title-screen slot button that is normally <b>Train Editor</b> and, while Shift is held,
 * becomes <b>Train Builder</b>.
 *
 * <p>The Editor is the default because it is the finished tool. The Builder is still being built,
 * so it sits behind Shift rather than in front of a player who never asked for it — but Shift is
 * now the whole gate. It used to also require a dev build, which meant a shipped jar offered the
 * Builder by no route at all; anyone who wanted to try it had to build the mod from source. An
 * unfinished tool should not be the first thing a player meets on the title screen, and it should
 * not be unreachable either.</p>
 *
 * <p>One widget rather than two: the slot is only half a button wide (Video Tools takes the other
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
     * <p>Pure and package-visible for tests. Shift alone decides, on every build: the branch check
     * this used to carry ({@code VersionInfo.BRANCH}, baked at build time) made the Builder
     * unreachable from a released jar, which is a stronger statement than "hidden by default" and
     * not the one that was wanted. The slot still says <b>Train Editor</b> until a player holds a
     * modifier key, so nothing meets them by accident.</p>
     */
    public static boolean shouldRevealBuilder(boolean shiftDown) {
        return shiftDown;
    }

    private boolean builderRevealed() {
        return shouldRevealBuilder(Screen.hasShiftDown());
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
