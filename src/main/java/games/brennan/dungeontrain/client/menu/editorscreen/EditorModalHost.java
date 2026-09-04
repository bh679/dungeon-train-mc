package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.menu.MenuEntryDispatcher;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.client.menu.MenuScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The inventory screen's own stack of {@link MenuScreen}s — pickers, confirms, the package list —
 * drawn as a centred modal over the panel, plus the typing field their {@code TypeArg} rows open.
 *
 * <p>Implements the dispatcher's {@link MenuEntryDispatcher.Host}, so a row inside a modal does
 * exactly what it does on the other menu surfaces. The two differences are what "close" means:
 * a {@code Run} closes the whole screen (it teleports or exits), while a modal's Back only pops
 * the modal.</p>
 */
public final class EditorModalHost implements MenuEntryDispatcher.Host {

    static final int ROW_H = 14;
    static final int HEADER_H = 14;
    static final int PAD = 4;
    static final int PANEL_W = 220;
    static final int SIDE_W = 160;
    static final int SIDE_GAP = 6;
    static final int BG = 0xE8000000;
    static final int MAX_TYPED = 32;
    /** Above every pane, below vanilla's tooltips at 400. */
    static final int MODAL_Z = 300;

    private final Deque<MenuScreen> stack = new ArrayDeque<>();
    private final Runnable closeScreen;
    private final Runnable afterCommand;

    private boolean typing;
    private String typedBuffer = "";
    private String typingPrefix = "";
    private String typingSuffix = "";
    private int typingRow = -1;
    private int typingSub;

    private int hoveredRow = -1;
    private int hoveredSub;
    private boolean hoveredSide;
    private int scroll;
    private int sideScroll;

    // Geometry from the last render, for hit-testing.
    private int px, py, pw, ph, sx, sy, sw, sh;
    private List<CommandMenuEntry> entries = List.of();
    private List<CommandMenuEntry> sideEntries = List.of();

    public EditorModalHost(Runnable closeScreen, Runnable afterCommand) {
        this.closeScreen = closeScreen;
        this.afterCommand = afterCommand;
    }

    public boolean isOpen() {
        return !stack.isEmpty();
    }

    public boolean isTyping() {
        return typing;
    }

    public void open(MenuScreen screen) {
        stack.push(screen);
        scroll = 0;
        sideScroll = 0;
        hoveredRow = -1;
    }

    /** Pop the top modal (or cancel typing). Answers whether anything was there to pop. */
    public boolean pop() {
        if (typing) {
            cancelTyping();
            return true;
        }
        if (stack.isEmpty()) return false;
        stack.pop();
        scroll = 0;
        sideScroll = 0;
        hoveredRow = -1;
        return true;
    }

    public void closeAll() {
        stack.clear();
        cancelTyping();
    }

    // ---- Host ----

    @Override public void runAndClose(String command) {
        CommandRunner.run(command);
        closeScreen.run();
    }

    @Override public void runAndStay(String command) {
        CommandRunner.run(command);
        afterCommand.run();
    }

    @Override public void drillIn(MenuScreen target) {
        open(target);
    }

    @Override public void goBack() {
        if (!pop()) closeScreen.run();
    }

    @Override public void beginTyping(String argName, String commandPrefix, String commandSuffix, String initialBuffer) {
        typing = true;
        typedBuffer = initialBuffer == null ? "" : initialBuffer;
        if (typedBuffer.length() > MAX_TYPED) typedBuffer = typedBuffer.substring(0, MAX_TYPED);
        typingPrefix = commandPrefix;
        typingSuffix = commandSuffix == null ? "" : commandSuffix;
        typingRow = hoveredRow;
        typingSub = hoveredSub;
    }

    /** Dispatch an entry from outside any modal (a pane row, an icon) through this host. */
    public void dispatch(CommandMenuEntry entry, int subIdx) {
        hoveredRow = -1;
        MenuEntryDispatcher.dispatch(entry, subIdx, this, Screen.hasShiftDown());
    }

    // ---- typing ----

    public void cancelTyping() {
        typing = false;
        typedBuffer = "";
        typingRow = -1;
    }

    /** Send the typed command; the screen closes, as the old menu did after a typed submit. */
    public void submitTyped() {
        if (!typing || typedBuffer.isEmpty()) return;
        String cmd = typingPrefix + " " + typedBuffer;
        if (!typingSuffix.isEmpty()) cmd = cmd + " " + typingSuffix;
        typing = false;
        typedBuffer = "";
        runAndClose(cmd);
    }

    public void backspace() {
        if (!typing || typedBuffer.isEmpty()) return;
        typedBuffer = typedBuffer.substring(0, typedBuffer.length() - 1);
    }

    /** Same allowlist as the panels: the only free-text arguments are names. */
    public boolean charTyped(char codePoint) {
        if (!typing) return false;
        char c = Character.toLowerCase(codePoint);
        if (((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') && typedBuffer.length() < MAX_TYPED) {
            typedBuffer = typedBuffer + c;
        }
        return true;
    }

    /** The typing state for the painter, or null. Typing outside a modal is drawn by the pane. */
    public MenuRowPainter.Typing typingFor(boolean side) {
        if (!typing || side != hoveredSide) return null;
        return new MenuRowPainter.Typing(typingRow, typingSub, typedBuffer);
    }

    public String typedBuffer() {
        return typedBuffer;
    }

    // ---- render ----

    public void render(GuiGraphics g, Font font, int screenW, int screenH, int mouseX, int mouseY) {
        if (stack.isEmpty()) return;
        MenuScreen top = stack.peek();
        entries = top.entries();
        MenuScreen side = top.sidePanel();
        sideEntries = side == null ? List.of() : side.entries();

        int maxRows = Math.max(1, (screenH - InventoryEditorLayout.HOTBAR_RESERVE - HEADER_H - PAD * 2 - 20) / ROW_H);
        int visible = Math.min(entries.size(), maxRows);
        int sideVisible = Math.min(sideEntries.size(), maxRows);
        scroll = clamp(scroll, 0, Math.max(0, entries.size() - visible));
        sideScroll = clamp(sideScroll, 0, Math.max(0, sideEntries.size() - sideVisible));

        pw = Math.min(PANEL_W, screenW - 20);
        ph = HEADER_H + visible * ROW_H + PAD * 2;
        sw = sideEntries.isEmpty() ? 0 : Math.min(SIDE_W, screenW - pw - 30);
        sh = sideEntries.isEmpty() ? 0 : HEADER_H + sideVisible * ROW_H + PAD * 2;
        int total = pw + (sw > 0 ? SIDE_GAP + sw : 0);
        px = (screenW - total) / 2;
        py = Math.max(10, (screenH - InventoryEditorLayout.HOTBAR_RESERVE - Math.max(ph, sh)) / 2);
        sx = px + pw + SIDE_GAP;
        sy = py;

        updateHover(mouseX, mouseY, visible, sideVisible);

        // Text is batched and flushed at the end of the frame, so a panel drawn later still lands
        // *under* text drawn earlier — which is how a picker ended up behind the settings rows it
        // was opened from. Commit what is already queued, then draw the whole modal above it.
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0, 0, MODAL_Z);
        g.fill(0, 0, screenW, screenH, 0x60000000);
        drawPanel(g, font, px, py, pw, ph, top.title(), entries, scroll, visible, false);
        if (sw > 0) {
            drawPanel(g, font, sx, sy, sw, sh, side.title(), sideEntries, sideScroll, sideVisible, true);
        }
        g.flush();
        g.pose().popPose();
    }

    private void drawPanel(GuiGraphics g, Font font, int x, int y, int w, int h, String title,
                           List<CommandMenuEntry> rows, int scrolled, int visible, boolean side) {
        g.fill(x, y, x + w, y + h, BG);
        g.renderOutline(x, y, w, h, 0xFF000000);
        String header = font.plainSubstrByWidth(title == null ? "" : title, w - PAD * 2);
        g.drawString(font, header, x + (w - font.width(header)) / 2, y + (HEADER_H - font.lineHeight) / 2,
            MenuRowPainter.TEXT_HEADER, true);
        MenuRowPainter.Typing typing = typingFor(side);
        for (int k = 0; k < visible; k++) {
            int idx = scrolled + k;
            int top = y + HEADER_H + PAD + k * ROW_H;
            boolean hov = hoveredSide == side && hoveredRow == idx;
            MenuRowPainter.drawRow(g, font, rows.get(idx), x + PAD, top, x + w - PAD, ROW_H - 1,
                idx, hov, hoveredSub, typing);
        }
    }

    private void updateHover(int mouseX, int mouseY, int visible, int sideVisible) {
        hoveredRow = -1;
        int main = hitRows(mouseX, mouseY, px, py, pw, entries, scroll, visible);
        if (main >= 0) {
            hoveredSide = false;
            hoveredRow = main >> 8;
            hoveredSub = main & 0xFF;
            return;
        }
        if (sw > 0) {
            int side = hitRows(mouseX, mouseY, sx, sy, sw, sideEntries, sideScroll, sideVisible);
            if (side >= 0) {
                hoveredSide = true;
                hoveredRow = side >> 8;
                hoveredSub = side & 0xFF;
            }
        }
    }

    /** {@code (row << 8) | sub}, or -1. */
    private static int hitRows(int mouseX, int mouseY, int x, int y, int w,
                               List<CommandMenuEntry> rows, int scrolled, int visible) {
        int left = x + PAD;
        int right = x + w - PAD;
        for (int k = 0; k < visible; k++) {
            int top = y + HEADER_H + PAD + k * ROW_H;
            if (mouseY < top || mouseY >= top + ROW_H - 1) continue;
            int idx = scrolled + k;
            int sub = MenuRowPainter.hitCell(rows.get(idx), mouseX, left, right);
            return sub < 0 ? -1 : (idx << 8) | sub;
        }
        return -1;
    }

    // ---- input ----

    /** A click while a modal is open; always consumed so nothing underneath reacts. */
    public boolean mouseClicked(double mouseX, double mouseY) {
        if (stack.isEmpty()) return false;
        if (typing) return true;
        if (hoveredRow < 0) return true;
        List<CommandMenuEntry> rows = hoveredSide ? sideEntries : entries;
        if (hoveredRow >= rows.size()) return true;
        int row = hoveredRow;
        int sub = hoveredSub;
        MenuEntryDispatcher.dispatch(rows.get(row), sub, this, Screen.hasShiftDown());
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (stack.isEmpty()) return false;
        int dir = scrollY > 0 ? -1 : 1;
        if (mouseX >= px && mouseX < px + pw) {
            scroll += dir;
            return true;
        }
        if (sw > 0 && mouseX >= sx && mouseX < sx + sw) {
            sideScroll += dir;
            return true;
        }
        return true;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }
}
