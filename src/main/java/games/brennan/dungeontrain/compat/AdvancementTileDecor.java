package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.client.TrackedAdvancements;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * How a Dungeon Train tile is decorated on the advancements screens, shared by the vanilla and
 * Better Advancements widget mixins so the two can't drift apart.
 *
 * <p>Two states, both only while the advancement is unearned: a tile this life has ruled out is
 * drawn at half opacity ({@link #beforeTile} sets the shader colour, {@link #afterIcon} restores
 * it), and a tile the player is tracking gets a yellow outline. Both widgets draw the frame at
 * {@code (x + widget.x + 3, y + widget.y)}, 26×26, then the icon — and vanilla then recurses into
 * child widgets in the same call, so the colour must be restored right after the icon, not at the
 * end of {@code draw}, or the children inherit the fade.</p>
 */
public final class AdvancementTileDecor {

    /** Opacity of a tile this life has ruled out. */
    private static final float DISQUALIFIED_ALPHA = 0.5f;
    /** Outline colour of a tracked tile — vanilla's "yellow" text colour, opaque. */
    private static final int TRACKED_OUTLINE = 0xFFFFFF55;
    /** Frame size both widgets blit. */
    private static final int TILE_SIZE = 26;
    /** Frame x offset from the widget origin, both widgets. */
    private static final int TILE_X_OFFSET = 3;

    private AdvancementTileDecor() {}

    /** Call at the top of the widget's {@code draw}: fades the frame + icon of a ruled-out tile. */
    public static void beforeTile(GuiGraphics g, ResourceLocation id, AdvancementProgress progress) {
        if (AdvancementHintText.isGreyedOut(id, progress)) {
            g.setColor(1.0f, 1.0f, 1.0f, DISQUALIFIED_ALPHA);
        }
    }

    /**
     * Call right after the widget renders its icon: restores the shader colour and outlines a
     * tracked tile. {@code originX/originY} are the tab origin passed to {@code draw};
     * {@code widgetX/widgetY} the widget's own position fields.
     */
    public static void afterIcon(GuiGraphics g, ResourceLocation id, AdvancementProgress progress,
                                 int originX, int originY, int widgetX, int widgetY) {
        if (AdvancementHintText.isGreyedOut(id, progress)) {
            g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
        if (isTrackedAndUnearned(id, progress)) {
            int left = originX + widgetX + TILE_X_OFFSET;
            int top = originY + widgetY;
            g.renderOutline(left - 1, top - 1, TILE_SIZE + 2, TILE_SIZE + 2, TRACKED_OUTLINE);
        }
    }

    /**
     * Call at the end of {@code draw}: a no-op in the normal case (the colour was already restored
     * after the icon), but guarantees no half-alpha colour ever outlives this widget's draw if a
     * widget skips its icon block.
     */
    public static void afterDraw(GuiGraphics g, ResourceLocation id, AdvancementProgress progress) {
        if (AdvancementHintText.isGreyedOut(id, progress)) {
            g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    private static boolean isTrackedAndUnearned(ResourceLocation id, AdvancementProgress progress) {
        if (progress != null && progress.isDone()) return false;
        return TrackedAdvancements.isTracked(id);
    }
}
