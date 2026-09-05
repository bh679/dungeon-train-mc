package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** The rotating model of the selected template, above the data sheet. */
public final class PreviewPane {

    static final int BACKDROP = 0xFF101418;
    static final int CAPTION = 0xFFFFEEBB;
    static final int HINT = 0x80FFFFFF;
    /** No border to saw through here, so the model may fill most of the pane. */
    static final float FILL = 0.90F;

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

    /** As above at {@code seq} of the build's relay history; 0 is the build as it is now. */
    public static void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, TemplateArt art,
                            String name, float yaw, EditorScreenTheme theme, int relayId, int seq) {
        g.fill(r.x(), r.y(), r.right(), r.bottom(), BACKDROP);
        boolean drawn = relayId > 0
            && RelayBuildPreviews.draw(g, relayId, seq, r.x(), r.y(), r.w(), r.h(), yaw, FILL);
        // A version is only ever the relay's picture: the local file is the build as it is now.
        if (!drawn && seq != 0) {
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
}
