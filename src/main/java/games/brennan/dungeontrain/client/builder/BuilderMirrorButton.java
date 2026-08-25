package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMirrorFlags;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.menu.PauseMenuActionButton;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Locale;

/**
 * One mirror-axis cell in the builder pause menu's tools column — {@code X}, {@code Y},
 * {@code Z}, or the Shift-revealed {@code V}.
 *
 * <p>Unlike every other button on that panel, this one does <b>not</b> close the menu. Mirroring
 * is a set of toggles you reach for two or three at a time, and
 * {@link BuilderPauseMenuHandler}'s {@code runCommand} closes the screen before dispatching (it
 * has to — in singleplayer the integrated server is paused while a pause screen is up, so a
 * queued command isn't processed until it closes). Reset / Clear / Delete want that; a toggle
 * row does not.</p>
 *
 * <p>So the command is sent and the screen left alone. Two consequences, both fine:</p>
 * <ul>
 *   <li>The cell can't wait for the server to answer before relighting, so it writes the new
 *       value into {@link BuilderBoundsState} itself and reads its tint back from there every
 *       frame. The server pushes a fresh {@code BuilderBoundsPacket} after every mirror toggle
 *       ({@code EditorCommand.runMirrorAtPosition}), which overwrites the guess with the
 *       authoritative value.</li>
 *   <li>In singleplayer the blocks don't actually mirror until the player leaves the menu — which
 *       is the first moment they could have seen it anyway, since the pause screen blurs the
 *       world behind it.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderMirrorButton extends PauseMenuActionButton {

    /** A mirrored axis is tinted green; the panel has no room for an "[ON]" suffix. */
    private static final float ON_R = 0.35F;
    private static final float ON_G = 1.0F;
    private static final float ON_B = 0.35F;

    private final String axis;

    public BuilderMirrorButton(int x, int y, int width, int height, String axis,
                               boolean visibleWhenShift) {
        super(x, y, width, height, Component.literal(axis.toUpperCase(Locale.ROOT)),
                1.0F, 1.0F, 1.0F, visibleWhenShift, b -> toggle(axis));
        this.axis = axis;
    }

    /**
     * Flip one axis: guess the new state locally so the cell responds to the click, then tell the
     * server. Split out as a static so the click handler can be passed to {@code super(...)}
     * before {@code this} exists.
     */
    private static void toggle(String axis) {
        BuilderMirrorFlags flags = BuilderBoundsState.mirror();
        boolean next = !flags.isOn(axis);
        BuilderBoundsState.setMirror(flags.with(axis, next));
        CommandRunner.run(commandFor(axis, next));
    }

    /**
     * The command that sets {@code axis} to {@code on}. Pure, so the unit test can pin the
     * command shape without standing up a client.
     */
    static String commandFor(String axis, boolean on) {
        return "dungeontrain editor mirror " + axis + (on ? " on" : " off");
    }

    private boolean lit() {
        return BuilderBoundsState.mirror().isOn(this.axis);
    }

    @Override
    protected float tintR() {
        return lit() ? ON_R : 1.0F;
    }

    @Override
    protected float tintG() {
        return lit() ? ON_G : 1.0F;
    }

    @Override
    protected float tintB() {
        return lit() ? ON_B : 1.0F;
    }
}
