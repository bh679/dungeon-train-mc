package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderRoomResize;
import games.brennan.dungeontrain.client.menu.PauseMenuActionButton;
import games.brennan.dungeontrain.net.BuilderRoomSizePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * One axis of the open portal room's footprint, in the builder pause menu's tools column —
 * {@code L 21}, {@code H 7}, {@code W 13}.
 *
 * <p>A room is the only thing the builder edits that has a size worth changing; every carriage plot
 * is {@code CarriageDims}. Length is the dial that matters most, being how far a player walks
 * between the two corridor mouths.</p>
 *
 * <p>Left-click grows the axis, right-click shrinks it. A pair of arrow cells per axis would be the
 * obvious shape and there is no room for six cells at this width, so the second mouse button
 * carries the second direction — stated in the tooltip, since nothing about the cell shows it.</p>
 *
 * <p>Like {@link BuilderMirrorButton} this does not close the menu: a size is something you nudge
 * several times while watching the number, and the read-out comes from the authoritative
 * {@code BuilderBoundsPacket} the server pushes after each change rather than from a local guess.
 * In singleplayer the blocks don't restamp until the menu closes, which is also the first moment
 * they could have been seen — the pause screen blurs the world behind it.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderRoomSizeButton extends PauseMenuActionButton {

    private final BuilderRoomResize.Axis axis;

    public BuilderRoomSizeButton(int x, int y, int width, int height, BuilderRoomResize.Axis axis) {
        super(x, y, width, height, label(axis), 1.0F, 1.0F, 1.0F, /*visibleWhenShift*/ false,
                b -> step(axis, +1));
        this.axis = axis;
        setTooltip(Tooltip.create(Component.translatable(
                "gui.dungeontrain.builder.room_size.tooltip", axisName(axis))));
    }

    /**
     * Right-click shrinks. Handled here rather than through the click handler because
     * {@code AbstractButton} only ever reports button 0 — it treats anything else as not a click at
     * all, so without this the cell would only ever grow.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && this.visible && this.active && isMouseOver(mouseX, mouseY)) {
            step(axis, -1);
            playDownSound(net.minecraft.client.Minecraft.getInstance().getSoundManager());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Ask the server for one more or one fewer block on this axis.
     *
     * <p>Sends the wanted value rather than a delta, so a click that lands while an earlier one is
     * still in flight asks for a size rather than compounding into two steps. The server clamps and
     * answers with the truth; a value the room is already at costs a no-op.</p>
     */
    private static void step(BuilderRoomResize.Axis axis, int delta) {
        int current = currentOf(axis);
        if (current <= 0) {
            return;   // no room open — the row shouldn't be visible, but a stale click can land
        }
        DungeonTrainNet.sendToServer(new BuilderRoomSizePacket(axis.id(), current + delta));
    }

    @Override
    public Component getMessage() {
        int size = currentOf(axis);
        return size <= 0 ? label(axis)
                : Component.literal(prefix(axis) + " " + size);
    }

    /** The axis's extent in the room currently open, or 0 when there isn't one. */
    private static int currentOf(BuilderRoomResize.Axis axis) {
        List<BoundingBox> volumes = BuilderBoundsState.volumes();
        if (volumes.isEmpty()) {
            return 0;
        }
        BoundingBox box = volumes.get(0);
        return switch (axis) {
            case LENGTH -> box.getXSpan();
            case HEIGHT -> box.getYSpan();
            case WIDTH -> box.getZSpan();
        };
    }

    private static Component label(BuilderRoomResize.Axis axis) {
        return Component.literal(prefix(axis));
    }

    private static String prefix(BuilderRoomResize.Axis axis) {
        return switch (axis) {
            case LENGTH -> "L";
            case HEIGHT -> "H";
            case WIDTH -> "W";
        };
    }

    private static Component axisName(BuilderRoomResize.Axis axis) {
        return Component.translatable("gui.dungeontrain.builder.room_size.axis." + axis.id());
    }
}
