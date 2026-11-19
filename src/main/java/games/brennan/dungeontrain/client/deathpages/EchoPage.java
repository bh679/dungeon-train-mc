package games.brennan.dungeontrain.client.deathpages;

import games.brennan.dungeontrain.client.DeathPortraitRenderer;
import games.brennan.dungeontrain.client.live.deathpages.LiveDeathChrome;
import games.brennan.dungeontrain.echo.EchoSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import static games.brennan.dungeontrain.client.deathpages.EchoPaint.LABEL;
import static games.brennan.dungeontrain.client.deathpages.EchoPaint.MUTED;
import static games.brennan.dungeontrain.client.deathpages.EchoPaint.NAME;
import static games.brennan.dungeontrain.client.deathpages.EchoPaint.SELECTED;
import static games.brennan.dungeontrain.client.deathpages.EchoPaint.TRACK;

/**
 * "The echo": the death page about the echo this death left behind. On the left, always in view,
 * the echo itself: a large animated model acting out its personality, under an in-game name tag.
 * On the right, two tabs: Statistics ({@link EchoStatsTab}: personality, the trait bars and the
 * reaction-skill hexagon) and Inventory ({@link EchoInventoryTab}: armour, offhand, the armour bar,
 * a 3×3 grid and an item details panel). On Statistics, the carriage number sits under the echo's
 * feet, where the armour bar stands on Inventory.
 *
 * <p>Items, sprites and the model can't take the page fade's alpha, so they draw only once the page
 * has settled (the screen passes {@code settled}). Owns its model renderer: call {@link #clear()}
 * from the screen's {@code removed()}.</p>
 */
public final class EchoPage {

    private static final int EDGE = 6;
    /** Content height range: the smallest GUI (240 px tall) gets the minimum. */
    private static final int MIN_CONTENT_H = 122;
    private static final int MAX_CONTENT_H = 200;
    /** Head room the idle animation needs above the model, in model heights (looks, helmet). */
    private static final float PORTRAIT_FIT = 2.25F;
    /** The echo is drawn a little smaller than its column allows. */
    private static final float PORTRAIT_SCALE = 0.9F;
    /** Room the name tag takes at the top of the echo's column. */
    private static final int NAME_TAG_H = 12;
    /** The in-game name tag's backing: a quarter-black strip, as vanilla draws it. */
    private static final int NAME_TAG_BG = 0x40000000;
    private static final int PANEL_BG = 0xB80E0F12;
    /** Height of the tab bar, underline included, and the gap under it. */
    private static final int TAB_BAR_H = 12;
    private static final int TAB_GAP = 10;

    private enum Tab { STATS, INVENTORY }

    /**
     * The panel's measurements for the room it has: the echo's column is sized to the model, the
     * tabs take the rest of the width, and the card fills the room down to the footer.
     */
    private record Layout(int pad, int contentH, int portraitW, int portraitSize, int textW) {
        static Layout fit(int room, int width, int minTextW) {
            int pad = room >= 160 ? EDGE : 3;
            int contentH = Math.max(MIN_CONTENT_H, Math.min(MAX_CONTENT_H, room - 2 * pad));
            int size = Math.round((contentH - NAME_TAG_H - 6 - (EchoPaint.ARMOR_ICON + 4)) / PORTRAIT_FIT * PORTRAIT_SCALE);
            // The echo's column is about as wide as the model and its swings need; the tabs take
            // the rest of the width.
            int portraitW = Math.max(60, Math.min(120, Math.round(size * 1.7F)));
            int worn = 2 * (EchoPaint.SLOT + 4);
            int textW = Math.max(Math.max(EchoInventoryTab.MIN_W, minTextW), width - (EDGE + portraitW + worn + EDGE + EDGE));
            return new Layout(pad, contentH, portraitW, size, textW);
        }

        int panelW() {
            return EDGE + portraitW + 2 * (EchoPaint.SLOT + 4) + EDGE + textW + EDGE;
        }

        int panelH() {
            return pad + contentH + pad;
        }
    }

    private final LiveDeathChrome chrome;
    private final DeathPortraitRenderer portrait = new DeathPortraitRenderer();
    private final EchoStatsTab stats;
    private final EchoInventoryTab inventory;
    private Tab tab = Tab.STATS;
    private int statsTabX, inventoryTabX, tabY, statsTabW, inventoryTabW;

    public EchoPage(LiveDeathChrome chrome) {
        this.chrome = chrome;
        this.stats = new EchoStatsTab(chrome);
        this.inventory = new EchoInventoryTab(chrome);
    }

    /**
     * Draw the panel with its top at {@code y}, centred on {@code cx}; returns the y below it.
     * {@code depth} is the fall page's carriage count; the card grows to fill the room down to
     * {@code maxY} (where the footer begins) and up to {@code width} across.
     */
    public int draw(GuiGraphics g, EchoSummary echo, int depth, int cx, int y, int maxY, int width,
                    int mouseX, int mouseY, boolean settled) {
        Font font = chrome.font();
        Component statsLabel = Component.translatable("gui.stats");
        Component inventoryLabel = Component.translatable("container.inventory");
        Component carriageLabel = Component.translatable("gui.dungeontrain.death.narr.lbl_carriage").append(": ");
        int tabRowW = font.width(statsLabel) + 12 + font.width(inventoryLabel);
        Layout l = Layout.fit(maxY - y, width, Math.max(tabRowW, stats.minWidth(font, echo)));
        int panelW = l.panelW();
        int panelH = l.panelH();
        int x0 = cx - panelW / 2;
        int top = y + l.pad();
        int topH = l.contentH();

        // A plain dark backing, no frame.
        g.fill(x0, y, x0 + panelW, y + panelH, chrome.fade(PANEL_BG));

        // ---- The echo, always in view, with its name floating above it like an in-game name tag.
        // The Inventory tab stands the worn gear around it, like the vanilla inventory: armour left,
        // offhand right, the armour bar under its feet. That room is kept on both tabs, so the echo
        // never moves when the tab changes; the gear just isn't drawn on Statistics.
        boolean wornAround = tab == Tab.INVENTORY;
        int wornW = EchoPaint.SLOT + 4;
        int portX = x0 + EDGE;
        int portW = l.portraitW() + 2 * wornW;
        int feetY = top + topH - 4 - (EchoPaint.ARMOR_ICON + 4);
        inventory.begin();
        if (settled && portrait.available()) {
            g.enableScissor(portX + wornW, top, portX + portW - wornW, top + topH);
            portrait.renderEcho(g, echo.appearance(), echo.personality(), echo.reactionSpeed(),
                    portX + portW / 2, feetY, l.portraitSize());
            g.disableScissor();
        }
        String number = Integer.toString(depth);
        if (!wornAround) {
            // Statistics: "carriage: X" under the echo's feet, where the armour bar sits on Inventory.
            int cw = font.width(carriageLabel) + font.width(number);
            int cxl = portX + (portW - cw) / 2;
            int cy = top + topH - EchoPaint.ARMOR_ICON;
            g.drawString(font, carriageLabel, cxl, cy, chrome.fade(LABEL), false);
            g.drawString(font, number, cxl + font.width(carriageLabel), cy, chrome.fade(NAME), false);
        }
        if (wornAround) {
            int columnH = 4 * EchoPaint.SLOT + 3 * EchoPaint.GAP;
            int colY = feetY - columnH;
            inventory.drawWorn(g, echo, portX, colY, portX + portW - EchoPaint.SLOT, feetY - EchoPaint.SLOT,
                    portX + (portW - EchoPaint.ARMOR_BAR_W) / 2, top + topH - EchoPaint.ARMOR_ICON - 1, settled);
        }
        drawNameTag(g, font, echoName(), portX + portW / 2, top + 2);

        // ---- Tab bar: Statistics | Inventory.
        int tx = portX + portW + EDGE;
        int tw = l.textW();
        tabY = top + 2;
        statsTabX = tx;
        statsTabW = font.width(statsLabel);
        inventoryTabX = tx + statsTabW + 12;
        inventoryTabW = font.width(inventoryLabel);
        drawTab(g, font, statsLabel, statsTabX, tab == Tab.STATS);
        drawTab(g, font, inventoryLabel, inventoryTabX, tab == Tab.INVENTORY);
        g.fill(tx, tabY + TAB_BAR_H - 1, tx + tw, tabY + TAB_BAR_H, chrome.fade(TRACK));

        // ---- The open tab.
        int by = tabY + TAB_BAR_H + TAB_GAP;
        int bh = top + topH - by;
        if (tab == Tab.STATS) {
            stats.draw(g, echo, tx, by, tw, bh, settled);
        } else {
            inventory.drawGrid(g, echo, tx, by, tw, bh, mouseX, mouseY, settled);
        }
        return y + panelH;
    }

    private void drawTab(GuiGraphics g, Font font, Component label, int x, boolean open) {
        g.drawString(font, label, x, tabY, chrome.fade(open ? NAME : MUTED), false);
        if (open) {
            g.fill(x, tabY + TAB_BAR_H - 2, x + font.width(label), tabY + TAB_BAR_H, chrome.fade(SELECTED));
        }
    }

    /** Hover tooltips for the open tab. Call once settled. */
    public void drawTooltips(GuiGraphics g, int mouseX, int mouseY) {
        if (tab == Tab.STATS) {
            stats.tooltip(g, mouseX, mouseY);
        } else {
            inventory.tooltip(g, mouseX, mouseY);
        }
    }

    /** A click on a tab switches to it; in the Inventory tab, a click on a slot selects that item. */
    public boolean mouseClicked(double mx, double my) {
        if (my >= tabY - 2 && my < tabY + TAB_BAR_H) {
            if (mx >= statsTabX && mx < statsTabX + statsTabW) {
                tab = Tab.STATS;
                return true;
            }
            if (mx >= inventoryTabX && mx < inventoryTabX + inventoryTabW) {
                tab = Tab.INVENTORY;
                return true;
            }
        }
        return tab == Tab.INVENTORY && inventory.mouseClicked(mx, my);
    }

    /** Drop the cached model entity. Call from the screen's {@code removed()}. */
    public void clear() {
        portrait.clear();
    }

    /** "Echo of <you>", the name the echo wears in the world. */
    private static Component echoName() {
        return Component.translatable("echo.dungeontrain.mob_name", Minecraft.getInstance().getUser().getName());
    }

    /** The echo's name as an in-game name tag: white text on a quarter-black strip, centred on {@code cx}. */
    private void drawNameTag(GuiGraphics g, Font font, Component name, int cx, int y) {
        int w = font.width(name);
        int x = cx - w / 2;
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 300.0F);
        g.fill(x - 2, y - 1, x + w + 1, y + 9, chrome.fade(NAME_TAG_BG));
        g.drawString(font, name, x, y, chrome.fade(NAME), false);
        g.pose().popPose();
    }
}
