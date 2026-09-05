package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * One square tile of the browser: the template's model, its photo, or a slate, with the marks
 * that say what it is and where it stands.
 */
public final class TemplateTilePainter {

    static final int MODEL_BACKDROP = 0xFF14161C;
    static final int SLATE = 0xFF3A4048;
    static final int IDLE_DIM = 0x40000000;
    /** 30% black over a filtered-out tile, so it reads at about 70% of its neighbours. */
    static final int GHOST_DIM = 0x4D000000;
    static final int BORDER_IDLE = 0xFF000000;
    static final int BORDER_HOVER = 0xFFFFFFFF;
    static final int BORDER_SELECTED = 0xFFFFCC33;
    static final int BADGE_BG = 0xC0101010;
    static final int HERE = 0xFF55FF55;
    static final int DIRTY = 0xFFFFAA33;
    static final int NEW_BG = 0xFF2B2B2B;
    static final int NEW_TEXT = 0xFF55FF99;
    /** Share of the cell the model fills: no label strip here, so more than the Open grid. */
    static final float FILL = 0.70F;

    /**
     * What marks a tile carries.
     *
     * <p>{@code ghost} is the odd one out: it says the tile is not part of what the filters asked
     * for and is only here because the author is standing in it, so it is drawn faded rather than
     * badged — a build you can see through is one you can tell apart from the search results without
     * reading anything.</p>
     */
    public record Marks(boolean selected, boolean hovered, boolean here, boolean dirty, boolean group,
                        boolean ghost) {
        public Marks(boolean selected, boolean hovered, boolean here, boolean dirty, boolean group) {
            this(selected, hovered, here, dirty, group, false);
        }
    }

    private TemplateTilePainter() {}

    public static void draw(GuiGraphics g, Font font, TemplateArt art, String name, int weight,
                            int x, int y, int size, float yaw, Marks marks) {
        draw(g, font, art, name, weight, x, y, size, yaw, marks, 0);
    }

    /**
     * As above for a build that lives on the relay.
     *
     * <p>{@code relayId} is tried first and the local art second, because a builder's upload may
     * have both: a reviewer looking at their own profile has the file too, and the copy that came
     * down the wire is the one the relay actually holds. Zero means "no relay build here" and this
     * is the ordinary template path.</p>
     */
    public static void draw(GuiGraphics g, Font font, TemplateArt art, String name, int weight,
                            int x, int y, int size, float yaw, Marks marks, int relayId) {
        boolean drawn = false;
        if (relayId > 0) {
            g.fill(x, y, x + size, y + size, MODEL_BACKDROP);
            drawn = RelayBuildPreviews.draw(g, relayId, x + 1, y + 1, size - 2, size - 2, yaw, FILL);
        }
        if (!drawn && art != null) {
            g.fill(x, y, x + size, y + size, MODEL_BACKDROP);
            drawn = art.drawModel(g, x + 1, y + 1, size - 2, size - 2, yaw, FILL);
            if (!drawn) drawn = art.drawPhoto(g, x + 1, y + 1, size - 2, size - 2);
        }
        if (!drawn) {
            g.fill(x, y, x + size, y + size, SLATE);
            String initials = name.length() > 3 ? name.substring(0, 3) : name;
            g.drawString(font, initials, x + (size - font.width(initials)) / 2,
                y + (size - font.lineHeight) / 2, 0xFFB0B8C0, false);
        }
        // A ghost is dimmed whether or not it is hovered — the fade IS what says it was filtered
        // out — and the ordinary idle dim is skipped under it rather than stacked, which would take
        // it well past the 70% it is meant to read at.
        if (marks.ghost()) {
            g.fill(x, y, x + size, y + size, GHOST_DIM);
        } else if (!marks.hovered() && !marks.selected()) {
            g.fill(x, y, x + size, y + size, IDLE_DIM);
        }

        if (weight != EditorPlotLabelsPacket.NO_WEIGHT) {
            String w = Integer.toString(weight);
            int tw = font.width(w);
            g.fill(x + size - tw - 4, y + size - font.lineHeight - 1, x + size - 1, y + size - 1, BADGE_BG);
            g.drawString(font, w, x + size - tw - 2, y + size - font.lineHeight, 0xFFFFFFFF, false);
        }
        if (marks.group()) {
            g.blitSprite(EditorIcons.GROUP, x + size - 10, y + 2, 8, 8);
        }
        if (marks.here()) {
            g.fill(x + 2, y + size - 6, x + 6, y + size - 2, 0xFF000000);
            g.fill(x + 3, y + size - 5, x + 5, y + size - 3, HERE);
        }
        if (marks.dirty()) {
            g.fill(x + 2, y + 2, x + 6, y + 6, 0xFF000000);
            g.fill(x + 3, y + 3, x + 5, y + 5, DIRTY);
        }

        if (marks.selected()) {
            g.renderOutline(x, y, size, size, BORDER_SELECTED);
            g.renderOutline(x + 1, y + 1, size - 2, size - 2, BORDER_SELECTED);
        } else {
            g.renderOutline(x, y, size, size, marks.hovered() ? BORDER_HOVER : BORDER_IDLE);
        }
    }

    /** The "+" tile that creates a new template in this strip or group. */
    public static void drawNew(GuiGraphics g, Font font, int x, int y, int size, boolean hovered) {
        g.fill(x, y, x + size, y + size, NEW_BG);
        g.renderOutline(x, y, size, size, hovered ? BORDER_HOVER : BORDER_IDLE);
        g.renderOutline(x + 2, y + 2, size - 4, size - 4, hovered ? 0x80FFFFFF : 0x40FFFFFF);
        String plus = "+";
        g.pose().pushPose();
        g.pose().translate(x + size / 2.0F, y + size / 2.0F, 0);
        g.pose().scale(2.0F, 2.0F, 1.0F);
        g.drawString(font, plus, -font.width(plus) / 2, -font.lineHeight / 2, NEW_TEXT, false);
        g.pose().popPose();
    }
}
