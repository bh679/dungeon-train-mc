package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.EditorMenusMode;
import games.brennan.dungeontrain.client.EditorMenusModeState;
import games.brennan.dungeontrain.client.EditorQuickTogglesState;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * The X menu's quick toggles: the list the ▼ beside Settings drops down, one icon row each for
 * <b>Mirror</b> (pause every template's live mirroring), <b>Menus</b> (the world-space editor
 * panels) and <b>Frame</b> (the carriage shell round Contents plots). Green icon = on, grey = off.
 *
 * <p>Mirror and Frame are world settings and Menus is the player's Editor Menus mode; each row sends
 * the same command its Settings row does, so the server stays the one owner and the next packet
 * redraws the state. Menus is three-way in Settings (Auto / On / Off); here it reads on for Auto and
 * On, and switching it back on returns to whichever of those it was before Off.</p>
 *
 * <p>Layout and hit-testing are pure functions of the arrow's tab so they test headless.</p>
 */
final class EditorQuickMenu {

    /** One row of the dropdown. */
    enum Item {
        MIRROR(EditorScreenLang.QUICK_MIRROR, EditorScreenLang.QUICK_MIRROR_TIP, EditorIcons.FLIP),
        MENUS(EditorScreenLang.QUICK_MENUS, EditorScreenLang.QUICK_MENUS_TIP, mod("icon/quick_menus")),
        FRAME(EditorScreenLang.QUICK_FRAME, EditorScreenLang.QUICK_FRAME_TIP, mod("icon/quick_frame"));

        final String labelKey;
        final String tipKey;
        final ResourceLocation icon;

        Item(String labelKey, String tipKey, ResourceLocation icon) {
            this.labelKey = labelKey;
            this.tipKey = tipKey;
            this.icon = icon;
        }
    }

    /** A laid-out row. */
    record Row(Item item, int x, int y, int w) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + ROW_H;
        }
    }

    static final int ROW_H = 20;
    static final int PAD = 3;
    static final int W = 104;
    static final int ICON_BOX = 18;
    static final int ON_FILL = 0xFF55C855;
    static final int OFF_FILL = 0xFF8B8B8B;

    /** Where Menus goes back to when switched on again: Auto unless it was On before Off. */
    private static EditorMenusMode lastVisibleMode = EditorMenusMode.AUTO;

    private boolean open;

    boolean isOpen() {
        return open;
    }

    void toggle() {
        open = !open;
    }

    void close() {
        open = false;
    }

    /** The rows hung under {@code arrow}, right edge flush with its right edge. */
    static List<Row> layout(EditorTabBar.Tab arrow, InventoryEditorLayout.Rect strip) {
        int x = arrow.x() + arrow.w() - W;
        int y = strip.bottom() + 1 + PAD;
        List<Row> out = new ArrayList<>();
        for (Item item : Item.values()) {
            out.add(new Row(item, x + PAD, y, W - PAD * 2));
            y += ROW_H;
        }
        return out;
    }

    /** The whole dropdown panel's rectangle, rows and padding included. */
    static InventoryEditorLayout.Rect bounds(EditorTabBar.Tab arrow, InventoryEditorLayout.Rect strip) {
        int x = arrow.x() + arrow.w() - W;
        int y = strip.bottom() + 1;
        return new InventoryEditorLayout.Rect(x, y, W, Item.values().length * ROW_H + PAD * 2);
    }

    /** The row under the point, or null. */
    static Item hit(List<Row> rows, double px, double py) {
        for (Row r : rows) {
            if (r.contains(px, py)) return r.item();
        }
        return null;
    }

    /** Whether {@code item} is on right now, from the client mirrors of each setting. */
    static boolean isOn(Item item) {
        return switch (item) {
            case MIRROR -> EditorQuickTogglesState.mirrorOn();
            case MENUS -> EditorMenusModeState.menusVisible();
            case FRAME -> EditorQuickTogglesState.frameOn();
        };
    }

    /** The command that flips {@code item}. Remembers Menus' visible mode on the way to Off. */
    static String flipCommand(Item item) {
        return switch (item) {
            case MIRROR -> "dungeontrain editor mirror live " + (EditorQuickTogglesState.mirrorOn() ? "off" : "on");
            case FRAME -> "dungeontrain editor frame " + (EditorQuickTogglesState.frameOn() ? "off" : "on");
            case MENUS -> menusFlipCommand(EditorMenusModeState.mode());
        };
    }

    /** Menus: a visible mode goes Off (remembered); Off goes back to the remembered mode. */
    static String menusFlipCommand(EditorMenusMode current) {
        if (current != EditorMenusMode.OFF) {
            lastVisibleMode = current;
            return "dungeontrain editor editormenus " + EditorMenusMode.OFF.id();
        }
        return "dungeontrain editor editormenus " + lastVisibleMode.id();
    }

    void render(GuiGraphics g, Font font, EditorScreenTheme theme, EditorTabBar.Tab arrow,
                InventoryEditorLayout.Rect strip, int mouseX, int mouseY) {
        if (!open || arrow == null) return;
        InventoryEditorLayout.Rect b = bounds(arrow, strip);
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);   // over the panes and their item icons
        g.fill(b.x() - 1, b.y() - 1, b.right() + 1, b.bottom() + 1, theme.outline());
        g.fill(b.x(), b.y(), b.right(), b.bottom(), theme.tabActive());
        g.fill(b.x(), b.y(), b.right(), b.y() + 1, theme.bevelLight());
        g.fill(b.x(), b.y(), b.x() + 1, b.bottom(), theme.bevelLight());
        g.fill(b.right() - 1, b.y(), b.right(), b.bottom(), theme.bevelDark());
        g.fill(b.x(), b.bottom() - 1, b.right(), b.bottom(), theme.bevelDark());
        for (Row r : layout(arrow, strip)) {
            boolean on = isOn(r.item());
            if (r.contains(mouseX, mouseY)) g.fill(r.x(), r.y(), r.x() + r.w(), r.y() + ROW_H, theme.tabHover());
            int ix = r.x() + 1;
            int iy = r.y() + (ROW_H - ICON_BOX) / 2;
            g.fill(ix, iy, ix + ICON_BOX, iy + ICON_BOX, theme.bevelDark());
            g.fill(ix + 1, iy + 1, ix + ICON_BOX - 1, iy + ICON_BOX - 1, on ? ON_FILL : OFF_FILL);
            g.blitSprite(r.item().icon, ix + 1, iy + 1, 16, 16);
            int ty = r.y() + (ROW_H - font.lineHeight) / 2 + 1;
            g.drawString(font, EditorScreenLang.text(r.item().labelKey), ix + ICON_BOX + 4, ty, theme.tabTextActive(), false);
            String state = EditorScreenLang.text(on ? EditorScreenLang.QUICK_ON : EditorScreenLang.QUICK_OFF);
            g.drawString(font, state, r.x() + r.w() - 3 - font.width(state), ty,
                on ? 0xFF2B7A2B : theme.tabTextActive(), false);
        }
        g.pose().popPose();
    }

    private static ResourceLocation mod(String path) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path);
    }
}
