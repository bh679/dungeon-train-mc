package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderRelayPreview;
import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.text.NumberFormat;

/** The rotating model of the selected template, above the data sheet. */
public final class PreviewPane {

    static final int BACKDROP = 0xFF101418;
    static final int CAPTION = 0xFFFFEEBB;
    static final int HINT = 0x80FFFFFF;
    /** No border to saw through here, so the model may fill most of the pane. */
    static final float FILL = 0.90F;
    /** "Oversized": amber, so it reads as a property of the build rather than a load still coming. */
    static final int OVERSIZED = 0xFFFFAA00;

    private PreviewPane() {}

    public static void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, TemplateArt art,
                            String name, float yaw, EditorScreenTheme theme) {
        draw(g, font, r, art, name, yaw, theme, 0);
    }

    /** As above, preferring the blocks a relay build sent down over any local file of the same name. */
    public static void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, TemplateArt art,
                            String name, float yaw, EditorScreenTheme theme, int relayId) {
        draw(g, font, r, art, name, yaw, theme, relayId, 0);
    }

    /**
     * A Workbench build: the staged snapshot's own picture, else the relay's picture of the same row,
     * else the pending hint — see {@link games.brennan.dungeontrain.client.builder.WorkbenchPreviews}.
     */
    public static void drawStaged(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, String stagedId,
                                  String name, float yaw, EditorScreenTheme theme, int relayId) {
        g.fill(r.x(), r.y(), r.right(), r.bottom(), BACKDROP);
        boolean drawn = games.brennan.dungeontrain.client.builder.WorkbenchPreviews.draw(
            g, stagedId, r.x(), r.y(), r.w(), r.h(), yaw, FILL);
        if (!drawn && relayId > 0) {
            drawn = RelayBuildPreviews.draw(g, relayId, 0, r.x(), r.y(), r.w(), r.h(), yaw, FILL);
        }
        if (!drawn) drawn = drawOversized(g, font, r, relayId, 0);
        if (!drawn) {
            String pending = EditorScreenLang.text(EditorScreenLang.SHEET_PENDING);
            g.drawString(font, pending, r.x() + (r.w() - font.width(pending)) / 2,
                r.y() + (r.h() - font.lineHeight) / 2, HINT, false);
        }
        g.renderOutline(r.x(), r.y(), r.w(), r.h(), theme.outline());
    }

    /** The plot the author stands in as its blocks are now — see {@link games.brennan.dungeontrain.client.builder.LivePlotPreview}. */
    public static void drawLive(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, String templateKey,
                                String name, float yaw, EditorScreenTheme theme) {
        g.fill(r.x(), r.y(), r.right(), r.bottom(), BACKDROP);
        games.brennan.dungeontrain.client.builder.LivePlotPreview.draw(
            g, templateKey, r.x(), r.y(), r.w(), r.h(), yaw, FILL);
        if (!name.isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(name, r.w() - 6), r.x() + 3, r.y() + 2, CAPTION, true);
        }
        g.renderOutline(r.x(), r.y(), r.w(), r.h(), theme.outline());
    }

    /** As above at {@code seq} of the build's relay history; 0 is the build as it is now. */
    public static void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, TemplateArt art,
                            String name, float yaw, EditorScreenTheme theme, int relayId, int seq) {
        g.fill(r.x(), r.y(), r.right(), r.bottom(), BACKDROP);
        boolean drawn = relayId > 0
            && RelayBuildPreviews.draw(g, relayId, seq, r.x(), r.y(), r.w(), r.h(), yaw, FILL);
        // A version is only ever the relay's picture: the local file is the build as it is now.
        if (!drawn && seq != 0) {
            if (drawOversized(g, font, r, relayId, seq)) {
                if (!name.isEmpty()) {
                    g.drawString(font, font.plainSubstrByWidth(name, r.w() - 6), r.x() + 3, r.y() + 2, CAPTION, true);
                }
                g.renderOutline(r.x(), r.y(), r.w(), r.h(), theme.outline());
                return;
            }
            String pending = EditorScreenLang.text(EditorScreenLang.SHEET_PENDING);
            g.drawString(font, pending, r.x() + (r.w() - font.width(pending)) / 2,
                r.y() + (r.h() - font.lineHeight) / 2, HINT, false);
            if (!name.isEmpty()) {
                g.drawString(font, font.plainSubstrByWidth(name, r.w() - 6), r.x() + 3, r.y() + 2, CAPTION, true);
            }
            g.renderOutline(r.x(), r.y(), r.w(), r.h(), theme.outline());
            return;
        }
        if (!drawn) drawn = art != null && art.drawModel(g, r.x(), r.y(), r.w(), r.h(), yaw, FILL);
        if (!drawn && art != null) {
            drawn = art.drawPhoto(g, r.x(), r.y(), r.w(), r.h());
        }
        if (!drawn) drawn = drawOversized(g, font, r, relayId, seq);
        if (!drawn) {
            String pending = EditorScreenLang.text(name.isEmpty()
                ? EditorScreenLang.NOTHING_SELECTED : EditorScreenLang.SHEET_PENDING);
            g.drawString(font, pending, r.x() + (r.w() - font.width(pending)) / 2,
                r.y() + (r.h() - font.lineHeight) / 2, HINT, false);
        }
        if (!name.isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(name, r.w() - 6), r.x() + 3, r.y() + 2, CAPTION, true);
        }
        g.renderOutline(r.x(), r.y(), r.w(), r.h(), theme.outline());
    }

    /**
     * "Oversized" and the block count, centred, when this relay build is too big to preview — in
     * place of a dash that would read as a picture still on its way. False when it is not oversized.
     */
    private static boolean drawOversized(GuiGraphics g, Font font, InventoryEditorLayout.Rect r,
                                         int relayId, int seq) {
        BuilderRelayPreview.Oversize o = relayId > 0 ? RelayBuildPreviews.oversized(relayId, seq) : null;
        if (o == null) return false;
        String title = EditorScreenLang.text(EditorScreenLang.SHEET_OVERSIZED);
        String detail = font.plainSubstrByWidth(EditorScreenLang.text(EditorScreenLang.SHEET_OVERSIZED_BLOCKS,
            NumberFormat.getIntegerInstance().format(o.blocks())), r.w() - 6);
        int top = r.y() + (r.h() - font.lineHeight * 2 - 3) / 2;
        g.drawString(font, title, r.x() + (r.w() - font.width(title)) / 2, top, OVERSIZED, true);
        g.drawString(font, detail, r.x() + (r.w() - font.width(detail)) / 2, top + font.lineHeight + 3, HINT, false);
        return true;
    }
}
