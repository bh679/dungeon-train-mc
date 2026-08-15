package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderGhostMode;
import games.brennan.dungeontrain.client.menu.PauseMenuActionButton;
import games.brennan.dungeontrain.net.BuilderGhostModePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The scenery control in the builder pause menu's tools column: Ghost → None → Solid.
 *
 * <p>What it governs is the shell of the carriage you are in, the flatbed pads capping the group,
 * and the track under all of it — see {@link BuilderGhostMode}, which is where the three states are
 * defined and justified. Leaves the menu open on click for {@link BuilderMirrorButton}'s reason: a
 * toggle you have to reopen the menu to use twice is a toggle nobody uses twice, and a three-state
 * one you may want to click twice in a row.</p>
 *
 * <p><b>This one does need a server round trip</b>, unlike the two-state ghost toggle it replaces.
 * That one only chose whether to draw blocks that were there either way, so it could be a client
 * config and a per-player preference. Solid is the difference between a wall existing and not
 * existing — the server owns that, and everyone in the world has to see the same answer.</p>
 *
 * <p>So the label and the tint read from {@link BuilderGhostCellsState}, which is the server's
 * answer, rather than from anything set locally on click. A click that the server refuses (or that
 * arrives in a world that isn't a builder) leaves the button showing what is actually true, which is
 * the behaviour you want from a control whose state is somebody else's.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderGhostButton extends PauseMenuActionButton {

    /** Same green the mirror cells light with, so the two read as one kind of control. */
    private static final float GHOST_R = 0.35F;
    private static final float GHOST_G = 1.0F;
    private static final float GHOST_B = 0.35F;

    /** Amber for Solid: on, doing something, and not the default. */
    private static final float SOLID_R = 1.0F;
    private static final float SOLID_G = 0.75F;
    private static final float SOLID_B = 0.30F;

    public BuilderGhostButton(int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable(BuilderGhostCellsState.mode().labelKey()),
                1.0F, 1.0F, 1.0F, /*visibleWhenShift*/ false,
                b -> DungeonTrainNet.sendToServer(
                        new BuilderGhostModePacket(BuilderGhostCellsState.mode().next().id())));
    }

    /**
     * Re-read the label every frame.
     *
     * <p>The state lives on the server and arrives on a packet, so the click that changes it and the
     * change itself are not the same moment. Setting the label on click would show the state the
     * player asked for rather than the one they have — briefly wrong every time, and permanently
     * wrong for a click that didn't take.</p>
     */
    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Component wanted = Component.translatable(BuilderGhostCellsState.mode().labelKey());
        if (!wanted.equals(getMessage())) {
            setMessage(wanted);
        }
        super.renderWidget(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected float tintR() {
        return switch (BuilderGhostCellsState.mode()) {
            case GHOST -> GHOST_R;
            case SOLID -> SOLID_R;
            case NONE -> 1.0F;
        };
    }

    @Override
    protected float tintG() {
        return switch (BuilderGhostCellsState.mode()) {
            case GHOST -> GHOST_G;
            case SOLID -> SOLID_G;
            case NONE -> 1.0F;
        };
    }

    @Override
    protected float tintB() {
        return switch (BuilderGhostCellsState.mode()) {
            case GHOST -> GHOST_B;
            case SOLID -> SOLID_B;
            case NONE -> 1.0F;
        };
    }
}
