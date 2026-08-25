package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.structure.BuilderStructureRefresh;
import games.brennan.dungeontrain.client.menu.PauseMenuActionButton;
import games.brennan.dungeontrain.net.BuilderStructureRefreshPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The refresh control in the builder pause menu, directly under {@link BuilderStructureButton}:
 * Instant → On save → Never.
 *
 * <p>Its sibling decides <em>whether</em> the structures around the build are there; this decides
 * <em>how current</em> the ones made from the template you have open are. See
 * {@link BuilderStructureRefresh}. Kept as its own row rather than folded into the one above because
 * the two answer different questions and the panel is too narrow to say both on one line.</p>
 *
 * <p>Server state and a round trip for the same reason as its sibling — while the structures are
 * solid, refreshing them is the server rewriting blocks — so the label and tint read from
 * {@link BuilderBoundsState} rather than from anything set locally on click. Leaves the menu open on
 * click: a three-state control is one you may well want to click twice in a row.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderStructureRefreshButton extends PauseMenuActionButton {

    /** The green the mirror cells and Ghost light with — on, and the default. */
    private static final float INSTANT_R = 0.35F;
    private static final float INSTANT_G = 1.00F;
    private static final float INSTANT_B = 0.35F;

    /** Amber for On save: doing something, on a schedule of its own. */
    private static final float ON_SAVE_R = 1.00F;
    private static final float ON_SAVE_G = 0.75F;
    private static final float ON_SAVE_B = 0.30F;

    public BuilderStructureRefreshButton(int x, int y, int width, int height) {
        super(x, y, width, height, label(), 1.0F, 1.0F, 1.0F, /*visibleWhenShift*/ false,
                b -> DungeonTrainNet.sendToServer(new BuilderStructureRefreshPacket(
                        BuilderBoundsState.structureRefresh().next().id())));
    }

    private static Component label() {
        return Component.translatable(BuilderBoundsState.structureRefresh().labelKey());
    }

    /**
     * Re-read the label every frame — {@link BuilderStructureButton#renderWidget}'s reason: the state
     * lives on the server, so the click that asks for a change and the change are not one moment.
     */
    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Component wanted = label();
        if (!wanted.equals(getMessage())) {
            setMessage(wanted);
        }
        super.renderWidget(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected float tintR() {
        return switch (BuilderBoundsState.structureRefresh()) {
            case INSTANT -> INSTANT_R;
            case ON_SAVE -> ON_SAVE_R;
            case NEVER -> 1.0F;
        };
    }

    @Override
    protected float tintG() {
        return switch (BuilderBoundsState.structureRefresh()) {
            case INSTANT -> INSTANT_G;
            case ON_SAVE -> ON_SAVE_G;
            case NEVER -> 1.0F;
        };
    }

    @Override
    protected float tintB() {
        return switch (BuilderBoundsState.structureRefresh()) {
            case INSTANT -> INSTANT_B;
            case ON_SAVE -> ON_SAVE_B;
            case NEVER -> 1.0F;
        };
    }
}
