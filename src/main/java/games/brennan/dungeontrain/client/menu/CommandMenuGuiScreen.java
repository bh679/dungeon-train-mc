package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The command menu as a real {@link Screen}.
 *
 * <p>Replaces the world-space decal that {@code CommandMenuRenderer} used to draw and the
 * camera-ray hover that {@code CommandMenuRaycast} used to resolve. Everything above the
 * rendering layer is unchanged: {@link CommandMenuState} still owns the navigation stack,
 * dispatch, and typing buffer, and every {@link MenuScreen} still describes its rows
 * declaratively. Only "where do the pixels go" and "what did the mouse hit" moved.</p>
 *
 * <h2>The hotbar</h2>
 * <p>The vanilla HUD keeps drawing while a screen is open — {@code GameRenderer.render} calls
 * {@code gui.render} gated on {@code isGameLoadFinished()}, not on {@code screen == null}, and
 * draws the screen afterwards. So the hotbar is already on screen behind this panel and we
 * neither draw nor hide it.</p>
 *
 * <p>What a {@code Screen} <i>does</i> swallow is the input that drives the hotbar, so
 * {@link #mouseScrolled} and {@link #keyPressed} hand the wheel and the 1-9 keys back to
 * {@link Inventory}. This is load-bearing rather than a nicety: the {@code Blocks: + held} rows
 * take whichever block the author is holding, so a menu that froze the hotbar would break that
 * feature outright. Slot changes need no packet of ours —
 * {@code MultiPlayerGameMode.tick} sends {@code ServerboundSetCarriedItemPacket} whenever
 * {@code Inventory.selected} drifts, and it ticks regardless of any open screen.</p>
 *
 * <p>{@link #isPauseScreen()} returns false so the world keeps running underneath — the train
 * has to keep moving while an author edits.</p>
 */
public final class CommandMenuGuiScreen extends Screen {

    // Colours carried over from the world-space renderer so the menu reads the same.
    private static final int PANEL_BG      = 0xD0000000;
    private static final int SCREEN_DIM    = 0x80101010;
    private static final int CELL_IDLE     = MenuRowPainter.CELL_IDLE;
    private static final int CELL_HOVER    = MenuRowPainter.CELL_HOVER;
    private static final int TEXT_HEADER   = MenuRowPainter.TEXT_HEADER;
    private static final int SCROLLBAR_TRACK = 0x40FFFFFF;
    private static final int SCROLLBAR_THUMB = 0xC0FFEEBB;

    /** Horizontal padding inside a cell before its label starts. */
    private static final int CELL_PAD_X = MenuRowPainter.CELL_PAD_X;

    /**
     * Distance from the top of the screen to the top of the panel.
     *
     * <p>The panel is anchored to its top edge rather than centred, so its height changing does
     * not move it. Rows appear and disappear constantly here — switching tab, or toggling Walls,
     * which reveals or hides the Copies / Door Wall / Exits rows beneath it — and under vertical
     * centring every one of those shifts every row on screen, so the button under the cursor is
     * no longer the button that was there a moment ago. Pinned at the top, added rows grow
     * downwards and everything above them stays put.</p>
     */
    private static final int PANEL_TOP = 22;

    /** Width of the scrollbar drawn inside the panel's right edge when a list overflows. */
    private static final int SCROLLBAR_W = 3;

    /** Clear space kept between the panels and the window edge. */
    private static final int EDGE_MARGIN = 6;

    /** Floor for a panel's width when everything is scaled down to fit a narrow window. */
    private static final int MIN_PANEL_W = 60;

    /** Side of the square header button — a row's height, so it reads as one more control. */
    private static final int HEADER_BTN = CommandMenuLayout.ROW_H;

    /** Authored side of the header button's sprite; drawn 1:1 so it stays crisp. */
    private static final int HEADER_ICON = 16;

    // Panel geometry, recomputed each frame in render() and read by the hit-test.
    private int mainX, mainY, mainW, mainH;
    private int sideX, sideY, sideW, sideH;

    // The main panel's header button (MenuScreen#headerAction), if the screen has one.
    private int headerBtnX, headerBtnY;
    private boolean headerHovered;

    // Scroll state. `sticky` rows stay pinned at the top; the rest scroll under them.
    private int mainSticky, mainScroll, mainVisible, mainMaxScroll;
    private int sideScroll, sideVisible, sideMaxScroll;

    /**
     * Breadcrumb at the last rebuild, used to reset scroll on navigation — drilling into a long
     * list part-scrolled from the previous one would start it halfway down.
     */
    private String lastBreadcrumb = "";

    public CommandMenuGuiScreen() {
        super(Component.literal("Dungeon Train"));
    }

    /** The world keeps ticking — an author needs the train moving while they edit. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        // Guarded inside close(): it no-ops when already closed, so the
        // close() -> setScreen(null) -> onClose() path cannot recurse.
        CommandMenuState.close();
        super.onClose();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    /**
     * How many rows fit on screen below the header, leaving the hotbar clear.
     *
     * <p>Lists here can be far longer than the window — the contents allow-list names every
     * registered content, and a portal room's Current tab runs to nineteen rows, which overflows
     * a 360px logical screen at GUI scale 3. Anything past this is reached by scrolling rather
     * than drawn off the bottom edge.</p>
     */
    private int maxVisibleRows() {
        int avail = this.height - PANEL_TOP - CommandMenuLayout.HOTBAR_RESERVE
            - CommandMenuLayout.HEADER_H - CommandMenuLayout.PANEL_PAD;
        return Math.max(1, avail / (CommandMenuLayout.ROW_H + CommandMenuLayout.ROW_GAP_PX));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    private void layout() {
        List<CommandMenuEntry> entries = CommandMenuState.entries();

        // Reset scroll when the player navigates, so a drilldown never opens part-scrolled.
        String crumb = CommandMenuState.breadcrumb();
        if (!crumb.equals(lastBreadcrumb)) {
            mainScroll = 0;
            sideScroll = 0;
            lastBreadcrumb = crumb;
        }

        int room = maxVisibleRows();

        MenuScreen top = CommandMenuState.mainScreen();
        mainSticky = top == null ? 0 : clamp(top.stickyRows(), 0, entries.size());
        int scrollable = entries.size() - mainSticky;
        mainVisible = Math.max(0, Math.min(scrollable, room - mainSticky));
        mainMaxScroll = Math.max(0, scrollable - mainVisible);
        mainScroll = clamp(mainScroll, 0, mainMaxScroll);
        mainW = CommandMenuLayout.panelPixelWidth(CommandMenuState.mainPanelWidth());
        mainH = CommandMenuLayout.pixelHeight(mainSticky + mainVisible);

        boolean hasSide = CommandMenuState.hasSidePanel();
        if (hasSide) {
            int sideTotal = CommandMenuState.sideEntries().size();
            sideVisible = Math.min(sideTotal, room);
            sideMaxScroll = Math.max(0, sideTotal - sideVisible);
            sideScroll = clamp(sideScroll, 0, sideMaxScroll);
            sideW = CommandMenuLayout.panelPixelWidth(CommandMenuState.sidePanelWidth());
            sideH = CommandMenuLayout.pixelHeight(sideVisible);
        } else {
            sideVisible = 0;
            sideMaxScroll = 0;
            sideW = 0;
            sideH = 0;
        }

        // Shrink to fit rather than overflow. Screens declare a width in the abstract units
        // MenuScreen#panelWidth speaks, and a wide one beside its side panel can ask for more
        // than the window has: the package list wants 3.6 units (450px) plus a 200px side panel,
        // which does not fit the 640px logical width of a 1920 window at GUI scale 3. Centring
        // that unchecked put the left edge at a negative x and clipped BOTH panels off the
        // screen edges. Scaling both by the same factor keeps their relative proportions, and
        // cell labels already truncate to their cell.
        int gap = hasSide ? CommandMenuLayout.SIDE_GAP_PX : 0;
        int avail = Math.max(MIN_PANEL_W + gap, this.width - EDGE_MARGIN * 2);
        int totalW = mainW + gap + sideW;
        if (totalW > avail) {
            double f = (double) (avail - gap) / (double) (totalW - gap);
            mainW = Math.max(MIN_PANEL_W, (int) Math.floor(mainW * f));
            if (hasSide) sideW = Math.max(MIN_PANEL_W, (int) Math.floor(sideW * f));
            totalW = mainW + gap + sideW;
        }
        mainX = Math.max(EDGE_MARGIN, (this.width - totalW) / 2);

        // Top-anchored, not centred — see PANEL_TOP. Both panels share the top edge so the
        // side panel doesn't slide against the main one as either one's row count changes.
        mainY = PANEL_TOP;
        sideX = mainX + mainW + CommandMenuLayout.SIDE_GAP_PX;
        sideY = PANEL_TOP;

        headerBtnX = mainX + mainW - CommandMenuLayout.PANEL_PAD - HEADER_BTN;
        headerBtnY = mainY + (CommandMenuLayout.HEADER_H - HEADER_BTN) / 2;
    }

    /** The main screen's header action, or null when there is none or the menu is closed. */
    private static MenuHeaderAction headerAction() {
        MenuScreen top = CommandMenuState.mainScreen();
        return top == null ? null : top.headerAction();
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    /**
     * A flat dim, and deliberately nothing else.
     *
     * <p>The inherited implementation runs the blur post-effect over the frame behind the screen
     * and then paints the menu background texture. Both are wrong here: this panel is opened
     * <i>over the plot being edited</i>, and an author needs to see the blocks they are about to
     * act on. Blurring the build defeats the point of the menu. The dim is light enough to leave
     * the hotbar underneath readable, which the {@code Blocks: + held} rows depend on.</p>
     */
    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        gg.fill(0, 0, this.width, this.height, SCREEN_DIM);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        if (!CommandMenuState.isOpen()) {
            this.onClose();
            return;
        }

        layout();

        // super.render draws the background (our override above) and any widgets. It has to run
        // FIRST: calling it last painted the background over the panel we had just drawn.
        super.render(gg, mouseX, mouseY, partialTick);

        updateHover(mouseX, mouseY);

        List<CommandMenuEntry> entries = CommandMenuState.entries();
        MenuHeaderAction action = headerAction();
        drawPanel(gg, mainX, mainY, mainW, mainH, entries,
            CommandMenuState.breadcrumb(), action,
            CommandMenuState.hoveredIdx(), CommandMenuState.hoveredSubIdx(),
            mainSticky, mainScroll, mainVisible, mainMaxScroll);

        if (CommandMenuState.hasSidePanel()) {
            MenuScreen side = CommandMenuState.sideScreen();
            drawPanel(gg, sideX, sideY, sideW, sideH, CommandMenuState.sideEntries(),
                side != null ? side.title() : "", null,
                CommandMenuState.sideHoveredIdx(), CommandMenuState.sideHoveredSubIdx(),
                0, sideScroll, sideVisible, sideMaxScroll);
        }

        // The icon carries no text, so its name rides on the cursor while it is under it.
        if (headerHovered && action != null) {
            gg.renderTooltip(this.font, Component.literal(action.label()), mouseX, mouseY);
        }
    }

    private void drawPanel(
        GuiGraphics gg, int px, int py, int pw, int ph,
        List<CommandMenuEntry> entries, String title, MenuHeaderAction action,
        int hovered, int hoveredSub,
        int sticky, int scroll, int visible, int maxScroll
    ) {
        gg.fill(px, py, px + pw, py + ph, PANEL_BG);

        // The breadcrumb stays centred on the panel; with a button at the right it is clipped
        // symmetrically, to the width between the button and its mirror on the left, so a
        // shrunk panel can never run the text under the icon.
        String header = (title == null || title.isEmpty()) ? "Dungeon Train" : title;
        int reserve = CommandMenuLayout.PANEL_PAD + (action == null ? 0 : HEADER_BTN + CELL_PAD_X);
        int headerAvail = pw - reserve * 2;
        if (headerAvail > 0 && this.font.width(header) > headerAvail) {
            header = this.font.plainSubstrByWidth(header, headerAvail);
        }
        drawLabel(gg, header, px + pw / 2,
            py + (CommandMenuLayout.HEADER_H - this.font.lineHeight) / 2, TEXT_HEADER, true);

        if (action != null) drawHeaderButton(gg, action);

        // Pinned rows first, then the scrolled window beneath them. `slot` is the visual
        // position; `idx` is the index into entries, which is what hover and dispatch speak.
        int slot = 0;
        for (int i = 0; i < sticky; i++, slot++) {
            drawRow(gg, entries.get(i), px, py, pw, i, slot, hovered == i, hoveredSub);
        }
        for (int k = 0; k < visible; k++, slot++) {
            int idx = sticky + scroll + k;
            drawRow(gg, entries.get(idx), px, py, pw, idx, slot, hovered == idx, hoveredSub);
        }

        if (maxScroll > 0) {
            drawScrollbar(gg, px, py, pw, sticky, scroll, visible, maxScroll);
        }
    }

    /** The header icon button: tinted like a cell, with the sprite drawn 1:1 at its centre. */
    private void drawHeaderButton(GuiGraphics gg, MenuHeaderAction action) {
        gg.fill(headerBtnX, headerBtnY, headerBtnX + HEADER_BTN, headerBtnY + HEADER_BTN,
            headerHovered ? CELL_HOVER : CELL_IDLE);
        int pad = (HEADER_BTN - HEADER_ICON) / 2;
        // The sprite is authored white so the tint is the whole colour.
        int tint = action.tint();
        gg.setColor(((tint >> 16) & 0xFF) / 255f, ((tint >> 8) & 0xFF) / 255f,
            (tint & 0xFF) / 255f, ((tint >>> 24) & 0xFF) / 255f);
        gg.blitSprite(action.icon(), headerBtnX + pad, headerBtnY + pad, HEADER_ICON, HEADER_ICON);
        gg.setColor(1f, 1f, 1f, 1f);
    }

    /** A thin track inside the right edge, so a truncated list doesn't look like the whole list. */
    private void drawScrollbar(
        GuiGraphics gg, int px, int py, int pw, int sticky, int scroll, int visible, int maxScroll
    ) {
        int trackTop = py + CommandMenuLayout.rowTop(sticky);
        int trackH = visible * (CommandMenuLayout.ROW_H + CommandMenuLayout.ROW_GAP_PX);
        int x2 = px + pw - 1;
        int x1 = x2 - SCROLLBAR_W;
        gg.fill(x1, trackTop, x2, trackTop + trackH, SCROLLBAR_TRACK);

        int total = visible + maxScroll;
        int thumbH = Math.max(8, trackH * visible / total);
        int thumbY = trackTop + (trackH - thumbH) * scroll / maxScroll;
        gg.fill(x1, thumbY, x2, thumbY + thumbH, SCROLLBAR_THUMB);
    }

    /** One row of a panel, painted by the shared {@link MenuRowPainter}. */
    private void drawRow(
        GuiGraphics gg, CommandMenuEntry entry,
        int px, int py, int pw, int rowIndex, int slot, boolean hovered, int hoveredSub
    ) {
        int top = py + CommandMenuLayout.rowTop(slot);
        int left = px + CommandMenuLayout.PANEL_PAD;
        int right = px + pw - CommandMenuLayout.PANEL_PAD;
        MenuRowPainter.drawRow(gg, this.font, entry, left, top, right, CommandMenuLayout.ROW_H,
            rowIndex, hovered, hoveredSub, typing());
    }

    /** The open typing field, for the painter, or null when none is open. */
    private static MenuRowPainter.Typing typing() {
        if (!CommandMenuState.typingMode()) return null;
        return new MenuRowPainter.Typing(CommandMenuState.typingOriginRowIdx(),
            CommandMenuState.typingOriginSubIdx(), CommandMenuState.typedBuffer());
    }

    private void drawLabel(GuiGraphics gg, String text, int centerX, int y, int color, boolean shadow) {
        MenuRowPainter.drawLabel(gg, this.font, text, centerX, y, color, shadow);
    }

    // ------------------------------------------------------------------
    // Hit-testing — replaces CommandMenuRaycast
    // ------------------------------------------------------------------

    private void updateHover(int mouseX, int mouseY) {
        // The header button first: it sits in the band above row 0, which the row hit-test
        // never covers, so the two cannot both claim a click.
        headerHovered = headerAction() != null
            && over(mouseX, mouseY, headerBtnX, headerBtnY, HEADER_BTN, HEADER_BTN);
        if (headerHovered) {
            CommandMenuState.setHovered(-1, 0);
            CommandMenuState.setSideHovered(-1, 0);
            return;
        }

        long main = hitPanel(mouseX, mouseY, mainX, mainY, mainW, CommandMenuState.entries(),
            mainSticky, mainScroll, mainVisible);
        if (main >= 0) {
            CommandMenuState.setHovered((int) (main >> 32), (int) (main & 0xFFFFFFFFL));
            CommandMenuState.setSideHovered(-1, 0);
            return;
        }
        CommandMenuState.setHovered(-1, 0);

        if (CommandMenuState.hasSidePanel()) {
            long side = hitPanel(mouseX, mouseY, sideX, sideY, sideW, CommandMenuState.sideEntries(),
                0, sideScroll, sideVisible);
            if (side >= 0) {
                CommandMenuState.setSideHovered((int) (side >> 32), (int) (side & 0xFFFFFFFFL));
                return;
            }
        }
        CommandMenuState.setSideHovered(-1, 0);
    }

    /** True when the cursor is inside this panel's rectangle. */
    private static boolean over(int mouseX, int mouseY, int px, int py, int pw, int ph) {
        return mouseX >= px && mouseX < px + pw && mouseY >= py && mouseY < py + ph;
    }

    /**
     * Resolve a mouse position to {@code (rowIdx, subIdx)} packed into a long, or -1 on a miss.
     * Non-clickable cells (Labels, already-saved SaveActions) miss deliberately, matching the
     * old raycast so the dispatcher never sees a click on them.
     */
    private static long hitPanel(
        int mouseX, int mouseY, int px, int py, int pw, List<CommandMenuEntry> entries,
        int sticky, int scroll, int visible
    ) {
        if (entries.isEmpty()) return -1;
        int left = px + CommandMenuLayout.PANEL_PAD;
        int right = px + pw - CommandMenuLayout.PANEL_PAD;
        if (mouseX < left || mouseX >= right) return -1;

        for (int slot = 0; slot < sticky + visible; slot++) {
            int top = py + CommandMenuLayout.rowTop(slot);
            if (mouseY < top || mouseY >= top + CommandMenuLayout.ROW_H) continue;

            // Visual slot -> index into entries. Pinned rows map straight through; everything
            // below them is offset by the scroll position.
            int i = slot < sticky ? slot : sticky + scroll + (slot - sticky);
            if (i >= entries.size()) return -1;

            int sub = MenuRowPainter.hitCell(entries.get(i), mouseX, left, right);
            if (sub < 0) return -1;
            return ((long) i << 32) | (sub & 0xFFFFFFFFL);
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (headerHovered) {
            MenuHeaderAction action = headerAction();
            if (action != null) {
                CommandMenuState.activateHeader(action);
                return true;
            }
        }
        int hovered = CommandMenuState.hoveredIdx();
        if (hovered >= 0) {
            CommandMenuState.activate(hovered, CommandMenuState.hoveredSubIdx());
            return true;
        }
        int sideHovered = CommandMenuState.sideHoveredIdx();
        if (sideHovered >= 0) {
            CommandMenuState.activateSide(sideHovered, CommandMenuState.sideHoveredSubIdx());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * The wheel does one of two things, decided by where the cursor is.
     *
     * <p>Over a panel whose list overflows, it scrolls that list. Anywhere else it falls through
     * to the hotbar, via the same {@code Inventory.swapPaint} vanilla's own mouse handler calls,
     * so wrapping and direction match the game exactly.</p>
     *
     * <p>The panel wins where the two overlap because a list you cannot reach the bottom of is a
     * dead end, whereas the hotbar is always reachable by moving the cursor off the panel — or by
     * pressing 1-9, which never stops working.</p>
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int dir = scrollY > 0 ? -1 : 1;
        int mx = (int) mouseX;
        int my = (int) mouseY;

        if (mainMaxScroll > 0 && over(mx, my, mainX, mainY, mainW, mainH)) {
            mainScroll = clamp(mainScroll + dir, 0, mainMaxScroll);
            return true;
        }
        if (sideMaxScroll > 0 && CommandMenuState.hasSidePanel()
            && over(mx, my, sideX, sideY, sideW, sideH)) {
            sideScroll = clamp(sideScroll + dir, 0, sideMaxScroll);
            return true;
        }

        if (HotbarPassthrough.scroll(this.minecraft, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // The X keybinding is a toggle in game and stays one here. It cannot arrive via
        // KeyMapping.consumeClick while a screen is up (KeyConflictContext.IN_GAME), so
        // the screen matches it against the binding directly.
        if (CommandMenuKeyBindings.TOGGLE.matches(keyCode, scanCode)
            && !CommandMenuState.typingMode()) {
            this.onClose();
            return true;
        }

        if (CommandMenuState.typingMode()) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    CommandMenuState.submitTyped();
                    return true;
                }
                case GLFW.GLFW_KEY_ESCAPE -> {
                    CommandMenuState.cancelTyping();
                    return true;
                }
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    CommandMenuState.backspaceTyped();
                    return true;
                }
                default -> { /* printable characters arrive via charTyped */ }
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (HotbarPassthrough.key(this.minecraft, keyCode, scanCode)) return true;

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * Typed characters for the argument field. The allowlist matches
     * {@code CarriageVariant.NAME_PATTERN} — variant, plot and section names are the only
     * free-text arguments in the mod and all share that restriction, so anything outside it
     * is dropped rather than shown and later rejected.
     */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!CommandMenuState.typingMode()) return super.charTyped(codePoint, modifiers);
        char c = Character.toLowerCase(codePoint);
        if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
            CommandMenuState.appendTyped(c);
        }
        return true;
    }
}
