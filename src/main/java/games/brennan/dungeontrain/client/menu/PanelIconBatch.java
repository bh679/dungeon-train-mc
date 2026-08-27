package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the item icons a panel wants to draw, so they can be rendered afterwards through
 * vanilla's GUI item path instead of by hand.
 *
 * <h2>Why icons can't just be drawn inline</h2>
 *
 * <p>Everything else a panel draws — quads, text — is happy under the screen-space host's
 * transform. Items are not, and drawing them with {@code ItemRenderer.renderStatic} the way the
 * world-space path does produces nothing visible at all. {@link GuiGraphics#renderItem} does
 * three things that call does not, and any one of them alone is enough to lose the icon:</p>
 *
 * <ul>
 *   <li>it calls {@code Lighting.setupForFlatItems()} for 2D models, without which a flat sprite
 *       renders unlit — black on a dark panel, which reads as missing;</li>
 *   <li>it translates z by 150, a real depth offset. A panel-local {@code 0.002} lift is about a
 *       sixth of a pixel once scaled, so the model's own depth straddles the background fill and
 *       half of it fails the depth test;</li>
 *   <li>it applies the Y flip and winding that GUI item models expect.</li>
 * </ul>
 *
 * <p>So rather than reimplement those, the panel records where each icon goes and the host
 * replays the list through {@code renderItem} once its own transform is off the stack. The
 * layout stays shared with the world-space path — only the final draw differs.</p>
 */
public final class PanelIconBatch {

    /** Vanilla draws an item into a 16x16 box, so that is the size a scale of 1 gives. */
    private static final float VANILLA_ICON_PX = 16.0f;

    /** One recorded icon, already in screen pixels. */
    private record Icon(ItemStack stack, float screenX, float screenY, float sizePx) {}

    private final List<Icon> icons = new ArrayList<>();

    /**
     * Record an icon centred on {@code (localX, localY)} in panel-local units.
     *
     * <p>The position is pushed through {@code ps} immediately rather than stored as panel
     * coordinates, because by replay time that pose is gone. Taking it here also means nested
     * transforms a draw body has pushed (a popup's offset, say) are already folded in.</p>
     *
     * @param sizeUnits the icon's edge length in panel-local units
     */
    public void add(PoseStack ps, ItemStack stack, double localX, double localY, double sizeUnits) {
        if (stack == null || stack.isEmpty()) return;
        Matrix4f pose = ps.last().pose();
        Vector3f at = pose.transformPosition(new Vector3f((float) localX, (float) localY, 0.0f));
        // m00 is the pose's horizontal scale. The panel transform is a translate and a scale with
        // no rotation, so this is the units-to-pixels factor the icon should be sized by.
        float pxPerUnit = Math.abs(pose.m00());
        icons.add(new Icon(stack, at.x, at.y, (float) sizeUnits * pxPerUnit));
    }

    /** Draw everything recorded, then empty the batch ready for the next frame. */
    public void render(GuiGraphics gg) {
        for (Icon icon : icons) {
            float scale = icon.sizePx() / VANILLA_ICON_PX;
            gg.pose().pushPose();
            gg.pose().translate(icon.screenX(), icon.screenY(), 0.0f);
            gg.pose().scale(scale, scale, 1.0f);
            // renderItem places the box's top-left at (x, y); offset so the centre lands on the
            // point the panel actually asked for.
            gg.renderItem(icon.stack(), -8, -8);
            gg.pose().popPose();
        }
        icons.clear();
    }
}
