package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.client.TrackedAdvancements;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.advancements.AdvancementWidgetType;
import net.minecraft.resources.ResourceLocation;

/**
 * How a Dungeon Train tile is decorated on the advancements screens, shared by the vanilla and
 * Better Advancements widget mixins so the two can't drift apart.
 *
 * <p>Two states, both only while the advancement is unearned: a tile the player is tracking gets
 * a yellow halo — the tile's own frame sprite, drawn slightly larger and tinted yellow behind it —
 * and a tile this life has ruled out is drawn at half opacity ({@link #beforeTile} sets the shader
 * colour, {@link #afterIcon} restores it). Both widgets draw the frame at
 * {@code (x + widget.x + 3, y + widget.y)}, 26×26, then the icon — and vanilla then recurses into
 * child widgets in the same call, so the colour must be restored right after the icon, not at the
 * end of {@code draw}, or the children inherit the fade.</p>
 */
public final class AdvancementTileDecor {

    /** Opacity of a tile this life has ruled out. */
    private static final float DISQUALIFIED_ALPHA = 0.5f;
    /** Tint of the tracked halo — vanilla's "yellow" text colour. */
    private static final float HALO_R = 1.0f, HALO_G = 1.0f, HALO_B = 0.33f;
    /** How far the halo frame extends past the tile on each side. */
    private static final int HALO_PAD = 2;
    /** Frame size both widgets blit. */
    private static final int TILE_SIZE = 26;
    /** Frame x offset from the widget origin, both widgets. */
    private static final int TILE_X_OFFSET = 3;

    private AdvancementTileDecor() {}

    /**
     * Call at the top of the widget's {@code draw}: draws the tracked halo behind the tile, then
     * fades a ruled-out tile. {@code originX/originY} are the tab origin passed to {@code draw};
     * {@code widgetX/widgetY} the widget's own position fields.
     */
    public static void beforeTile(GuiGraphics g, AdvancementNode node, AdvancementProgress progress,
                                  int originX, int originY, int widgetX, int widgetY) {
        ResourceLocation id = node.holder().id();
        boolean faded = AdvancementHintText.isGreyedOut(id, progress);
        float alpha = faded ? DISQUALIFIED_ALPHA : 1.0f;
        if (isTrackedAndUnearned(id, progress)) {
            drawHalo(g, node, originX + widgetX + TILE_X_OFFSET, originY + widgetY, alpha);
        }
        if (faded) {
            g.setColor(1.0f, 1.0f, 1.0f, DISQUALIFIED_ALPHA);
        }
    }

    /**
     * The tile's own unobtained frame (task / goal / challenge shape), {@link #HALO_PAD} bigger on
     * every side and tinted yellow, so the highlight matches the box it sits behind. The plain
     * frame's neutral grey takes the tint cleanly. Drawn at the tile's own alpha: a faded tile over
     * a full-strength halo would read as a bright yellow tile, not a faded one.
     */
    private static void drawHalo(GuiGraphics g, AdvancementNode node, int left, int top, float alpha) {
        AdvancementType type = node.advancement().display().map(DisplayInfo::getType).orElse(AdvancementType.TASK);
        ResourceLocation frame = AdvancementWidgetType.UNOBTAINED.frameSprite(type);
        g.setColor(HALO_R, HALO_G, HALO_B, alpha);
        g.blitSprite(frame, left - HALO_PAD, top - HALO_PAD, TILE_SIZE + 2 * HALO_PAD, TILE_SIZE + 2 * HALO_PAD);
        g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** Call right after the widget renders its icon: restores the shader colour after a fade. */
    public static void afterIcon(GuiGraphics g, ResourceLocation id, AdvancementProgress progress) {
        if (AdvancementHintText.isGreyedOut(id, progress)) {
            g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
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
