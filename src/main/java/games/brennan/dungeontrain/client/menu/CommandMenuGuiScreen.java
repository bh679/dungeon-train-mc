package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
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
    private static final int CELL_IDLE     = 0x30FFFFFF;
    private static final int CELL_HOVER    = 0xB0FFCC33;
    private static final int TOGGLE_ON     = 0x8040AA40;
    private static final int TOGGLE_OFF    = 0x40FFFFFF;
    private static final int SAVED_GREY    = 0x40808080;
    private static final int HIGHLIGHT     = 0x80FFAA33;
    private static final int TYPING_BG     = 0xB033FF99;
    private static final int TEXT_NORMAL   = 0xFFFFFFFF;
    private static final int TEXT_ON_HOVER = 0xFF000000;
    private static final int TEXT_HEADER   = 0xFFFFEEBB;
    private static final int SCROLLBAR_TRACK = 0x40FFFFFF;
    private static final int SCROLLBAR_THUMB = 0xC0FFEEBB;

    /** Horizontal padding inside a cell before its label starts. */
    private static final int CELL_PAD_X = 2;

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

    // Panel geometry, recomputed each frame in render() and read by the hit-test.
    private int mainX, mainY, mainW, mainH;
    private int sideX, sideY, sideW, sideH;

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

        int totalW = mainW + (hasSide ? CommandMenuLayout.SIDE_GAP_PX + sideW : 0);
        mainX = (this.width - totalW) / 2;

        // Top-anchored, not centred — see PANEL_TOP. Both panels share the top edge so the
        // side panel doesn't slide against the main one as either one's row count changes.
        mainY = PANEL_TOP;
        sideX = mainX + mainW + CommandMenuLayout.SIDE_GAP_PX;
        sideY = PANEL_TOP;
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
        drawPanel(gg, mainX, mainY, mainW, mainH, entries,
            CommandMenuState.breadcrumb(),
            CommandMenuState.hoveredIdx(), CommandMenuState.hoveredSubIdx(),
            mainSticky, mainScroll, mainVisible, mainMaxScroll);

        if (CommandMenuState.hasSidePanel()) {
            MenuScreen side = CommandMenuState.sideScreen();
            drawPanel(gg, sideX, sideY, sideW, sideH, CommandMenuState.sideEntries(),
                side != null ? side.title() : "",
                CommandMenuState.sideHoveredIdx(), CommandMenuState.sideHoveredSubIdx(),
                0, sideScroll, sideVisible, sideMaxScroll);
        }
    }

    private void drawPanel(
        GuiGraphics gg, int px, int py, int pw, int ph,
        List<CommandMenuEntry> entries, String title, int hovered, int hoveredSub,
        int sticky, int scroll, int visible, int maxScroll
    ) {
        gg.fill(px, py, px + pw, py + ph, PANEL_BG);

        String header = (title == null || title.isEmpty()) ? "Dungeon Train" : title;
        drawLabel(gg, header, px + pw / 2,
            py + (CommandMenuLayout.HEADER_H - this.font.lineHeight) / 2, TEXT_HEADER, true);

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

    private void drawRow(
        GuiGraphics gg, CommandMenuEntry entry,
        int px, int py, int pw, int rowIndex, int slot, boolean hovered, int hoveredSub
    ) {
        int top = py + CommandMenuLayout.rowTop(slot);
        int left = px + CommandMenuLayout.PANEL_PAD;
        int right = px + pw - CommandMenuLayout.PANEL_PAD;
        int usable = right - left;

        double[] bounds = cellBoundaries(entry);
        CommandMenuEntry[] cells = cellsOf(entry);

        if (cells.length == 1) {
            drawCell(gg, cells[0], left, top, right, hovered, rowIndex, 0);
            return;
        }

        int prev = left;
        for (int c = 0; c < cells.length; c++) {
            int edge = (c == cells.length - 1)
                ? right
                : left + (int) Math.round(bounds[c] * usable);
            drawCell(gg, cells[c], prev, top, edge, hovered && hoveredSub == c, rowIndex, c);
            prev = edge;
        }
    }

    private void drawCell(
        GuiGraphics gg, CommandMenuEntry entry,
        int x1, int top, int x2, boolean hovered, int rowIndex, int subIdx
    ) {
        int bottom = top + CommandMenuLayout.ROW_H;
        boolean isLabel = entry instanceof CommandMenuEntry.Label;

        if (isTypingHere(rowIndex, subIdx)) {
            gg.fill(x1, top, x2, bottom, TYPING_BG);
            drawLabel(gg, CommandMenuState.typedBuffer() + "_", (x1 + x2) / 2,
                top + (CommandMenuLayout.ROW_H - this.font.lineHeight) / 2,
                TEXT_ON_HOVER, false);
            return;
        }

        int tint;
        if (hovered && !isLabel) {
            tint = CELL_HOVER;
        } else {
            int base = baseTintFor(entry);
            tint = base != 0 ? base : (isLabel ? 0 : CELL_IDLE);
        }
        if (tint != 0) gg.fill(x1, top, x2, bottom, tint);

        boolean dark = hovered && !isLabel;
        String label = labelFor(entry);
        int color = dark ? TEXT_ON_HOVER : TEXT_NORMAL;
        int textY = top + (CommandMenuLayout.ROW_H - this.font.lineHeight) / 2;
        int avail = (x2 - x1) - CELL_PAD_X * 2;
        if (avail > 0 && this.font.width(label) > avail) {
            label = this.font.plainSubstrByWidth(label, avail);
        }
        // Shadow only under light text. A black label's shadow is also black, and offset by a
        // pixel it reads as doubled, smeared text rather than as depth — which is exactly what
        // the hover state looked like.
        drawLabel(gg, label, (x1 + x2) / 2, textY, color, !dark);
    }

    /**
     * Centred text with explicit shadow control.
     *
     * <p>{@code GuiGraphics.drawCenteredString} always draws a shadow, which is why this exists.</p>
     */
    private void drawLabel(GuiGraphics gg, String text, int centerX, int y, int color, boolean shadow) {
        gg.drawString(this.font, text, centerX - this.font.width(text) / 2, y, color, shadow);
    }

    private static boolean isTypingHere(int rowIdx, int subIdx) {
        return CommandMenuState.typingMode()
            && CommandMenuState.typingOriginRowIdx() == rowIdx
            && CommandMenuState.typingOriginSubIdx() == subIdx;
    }

    /** Base tint, matching the world-space renderer so state reads identically. */
    private static int baseTintFor(CommandMenuEntry entry) {
        if (entry instanceof CommandMenuEntry.Toggle t)     return t.state() ? TOGGLE_ON : TOGGLE_OFF;
        if (entry instanceof CommandMenuEntry.SaveAction s) return s.saved() ? SAVED_GREY : TOGGLE_ON;
        if (entry instanceof CommandMenuEntry.Label)        return 0;
        if (entry instanceof CommandMenuEntry.Run r     && r.highlighted())  return HIGHLIGHT;
        if (entry instanceof CommandMenuEntry.Stay s    && s.highlighted())  return HIGHLIGHT;
        if (entry instanceof CommandMenuEntry.DrillIn d && d.highlighted())  return HIGHLIGHT;
        if (entry instanceof CommandMenuEntry.ClientAction c && c.highlighted()) return HIGHLIGHT;
        return 0;
    }

    private static String labelFor(CommandMenuEntry entry) {
        if (entry instanceof CommandMenuEntry.Toggle t) {
            return t.showStateText() ? t.label() + (t.state() ? " [ON]" : " [OFF]") : t.label();
        }
        return entry.label();
    }

    // ------------------------------------------------------------------
    // Row decomposition — one place that knows how a row splits into cells,
    // shared by the renderer and the hit-test so they cannot disagree.
    // ------------------------------------------------------------------

    private static CommandMenuEntry[] cellsOf(CommandMenuEntry entry) {
        if (entry instanceof CommandMenuEntry.Split s) {
            return new CommandMenuEntry[] { s.leftEntry(), s.rightEntry() };
        }
        if (entry instanceof CommandMenuEntry.Triple t) {
            return new CommandMenuEntry[] { t.leftEntry(), t.middleEntry(), t.rightEntry() };
        }
        if (entry instanceof CommandMenuEntry.Quad q) {
            return new CommandMenuEntry[] { q.e1(), q.e2(), q.e3(), q.e4() };
        }
        return new CommandMenuEntry[] { entry };
    }

    /** Panel-relative split fractions for a multi-cell row; empty for single-cell rows. */
    private static double[] cellBoundaries(CommandMenuEntry entry) {
        if (entry instanceof CommandMenuEntry.Split s) {
            return new double[] { s.leftFraction() };
        }
        if (entry instanceof CommandMenuEntry.Triple t) {
            return new double[] { t.leftFraction(), t.middleEnd() };
        }
        if (entry instanceof CommandMenuEntry.Quad q) {
            return new double[] { q.boundary1(), q.boundary2(), q.boundary3() };
        }
        return new double[0];
    }

    // ------------------------------------------------------------------
    // Hit-testing — replaces CommandMenuRaycast
    // ------------------------------------------------------------------

    private void updateHover(int mouseX, int mouseY) {
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

            CommandMenuEntry row = entries.get(i);
            if (row instanceof CommandMenuEntry.Label) return -1;

            CommandMenuEntry[] cells = cellsOf(row);
            double[] bounds = cellBoundaries(row);
            int usable = right - left;

            int sub = 0;
            for (int c = 0; c < bounds.length; c++) {
                if (mouseX >= left + (int) Math.round(bounds[c] * usable)) sub = c + 1;
            }

            CommandMenuEntry cell = cells[Math.min(sub, cells.length - 1)];
            if (cell instanceof CommandMenuEntry.Label) return -1;
            if (cell instanceof CommandMenuEntry.SaveAction sa && sa.saved()) return -1;

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

        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.getInventory().swapPaint(scrollY);
            return true;
        }
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

        if (hotbarKey(keyCode, scanCode)) return true;

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 1-9 select a hotbar slot, exactly as they would with no screen open. */
    private boolean hotbarKey(int keyCode, int scanCode) {
        if (this.minecraft == null || this.minecraft.player == null) return false;
        for (int i = 0; i < 9; i++) {
            if (this.minecraft.options.keyHotbarSlots[i].matches(keyCode, scanCode)) {
                this.minecraft.player.getInventory().selected = i;
                return true;
            }
        }
        return false;
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
