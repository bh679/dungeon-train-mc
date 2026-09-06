package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.config.EditorScreenTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The strip above the panel: {@code Current} far left, the browse pages, {@code My Builds},
 * then {@code Settings} and the Exit icon locked to the right.
 *
 * <p>Layout is a pure function of the label widths so it can be tested; painting is the
 * extruded bevel the mod's other tab buttons use.</p>
 */
public final class EditorTabBar {

    /** What a tab does when clicked. */
    public enum Kind { PAGE, EXIT }

    /** A laid-out tab. {@code page} is null for Exit. */
    public record Tab(Kind kind, EditorScreenPage page, String label, int x, int w) {
        public boolean contains(double px) {
            return px >= x && px < x + w;
        }
    }

    static final int PAD_X = 6;
    static final int GAP = 2;
    static final int EXIT_W = 18;
    static final int HERE = 0xFF55FF55;
    static final int DIRTY = 0xFFFFAA33;

    private EditorTabBar() {}

    /**
     * Lay the tabs out across {@code strip}. {@code widthOf} measures a label, so the layout is
     * testable with a fake font.
     */
    public static List<Tab> layout(InventoryEditorLayout.Rect strip, ToIntFunction<String> widthOf,
                                   List<String> labels) {
        // labels: one per browse page (ALL..DIMENSIONS), then settings
        List<Tab> out = new ArrayList<>();
        int x = strip.x();
        int i = 0;
        for (EditorScreenPage page : EditorScreenPage.values()) {
            if (page == EditorScreenPage.SETTINGS) continue;
            Tab t = tab(Kind.PAGE, page, labels.get(i++), x, widthOf);
            out.add(t);
            x = t.x() + t.w() + GAP;
        }
        // Right-locked: Exit at the very end, Settings just before it.
        int exitX = strip.right() - EXIT_W;
        int settingsW = widthOf.applyAsInt(labels.get(i)) + PAD_X * 2;
        int settingsX = Math.max(x, exitX - GAP - settingsW);
        out.add(new Tab(Kind.PAGE, EditorScreenPage.SETTINGS, labels.get(i), settingsX, settingsW));
        out.add(new Tab(Kind.EXIT, null, "", Math.max(settingsX + settingsW + GAP, exitX), EXIT_W));
        return out;
    }

    private static Tab tab(Kind kind, EditorScreenPage page, String label, int x, ToIntFunction<String> widthOf) {
        return new Tab(kind, page, label, x, widthOf.applyAsInt(label) + PAD_X * 2);
    }

    /** The tab under the point, or null. */
    public static Tab hit(List<Tab> tabs, InventoryEditorLayout.Rect strip, double px, double py) {
        if (py < strip.y() || py >= strip.bottom()) return null;
        for (Tab t : tabs) {
            if (t.contains(px)) return t;
        }
        return null;
    }

    public static void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect strip, List<Tab> tabs,
                            EditorScreenPage active, EditorScreenPage herePage, boolean dirty,
                            Tab hovered, EditorScreenTheme theme) {
        for (Tab t : tabs) {
            boolean isActive = t.kind() == Kind.PAGE && t.page() == active;
            int fill = isActive ? theme.tabActive() : (t == hovered ? theme.tabHover() : theme.tabIdle());
            int x0 = t.x();
            int y0 = isActive ? strip.y() - 2 : strip.y();
            int x1 = t.x() + t.w();
            int y1 = strip.bottom() + (isActive ? 1 : 0);
            g.fill(x0, y0, x1, y1, fill);
            g.fill(x0, y0, x1, y0 + 1, theme.bevelLight());
            g.fill(x0, y0, x0 + 1, y1, theme.bevelLight());
            g.fill(x1 - 1, y0, x1, y1, theme.bevelDark());
            if (!isActive) g.fill(x0, y1 - 1, x1, y1, theme.bevelDark());

            int textColour = isActive ? theme.tabTextActive() : theme.tabText();
            if (t.kind() == Kind.EXIT) {
                g.blitSprite(EditorIcons.EXIT, x0 + (t.w() - 16) / 2, strip.y() + (strip.h() - 16) / 2 + 1, 16, 16);
                continue;
            }
            int tx = x0 + PAD_X;
            int ty = strip.y() + (strip.h() - font.lineHeight) / 2 + 1;
            g.drawString(font, t.label(), tx, ty, textColour, false);
            if (t.kind() == Kind.PAGE && t.page() == herePage) {
                g.fill(x1 - 6, ty + 1, x1 - 3, ty + 4, HERE);
            }
        }
    }
}
