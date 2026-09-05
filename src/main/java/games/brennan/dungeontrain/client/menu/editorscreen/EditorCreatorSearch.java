package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.net.BuilderCreatorResultsPacket;
import games.brennan.dungeontrain.net.BuilderCreatorSearchPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * <b>Find a builder</b>, without leaving the editor screen.
 *
 * <p>The same search {@code BuilderCreatorSearchScreen} runs from the pause menu — same packets,
 * same debounce, same stars — drawn as a panel over the browser instead of as a screen of its own.
 * Picking a builder does not hand the player to another screen: it loads that player's uploaded
 * builds into the browser grid behind this panel, which is the thing they came here to look at.</p>
 *
 * <p>An empty box lists the builders this player has starred, because a star exists to save exactly
 * the search this panel asks for. Typing replaces them with results; clearing brings them back.</p>
 *
 * <p>Searches go out on a short delay rather than on every keystroke — each is a round trip through
 * the server to the relay, and a name is typed faster than one completes. Answers carry the query
 * they belong to, so one that arrives after the player has typed past it is dropped.</p>
 */
public final class EditorCreatorSearch {

    static final int WIDTH = 236;
    static final int MAX_HEIGHT = 200;
    static final int PAD = 4;
    static final int HEADER_H = 12;
    static final int FIELD_H = 14;
    static final int ROW_H = 13;
    static final int STAR_W = 14;
    static final int NOTE_H = 11;
    static final int BG = 0xF0101010;
    static final int FIELD_BG = 0x40FFFFFF;
    static final int NOTE_TEXT = 0xFFA0A0A0;
    static final int MAX_QUERY = 32;
    /** Ticks of quiet before a search is sent — long enough to finish a name, short enough to feel live. */
    static final int SEARCH_DELAY_TICKS = 8;

    /** What a click did, for the screen that hosts the panel. */
    public enum Outcome { NONE, CONSUMED, PICKED, CLEARED, ALL }

    public record Result(Outcome outcome, BuilderCreatorResultsPacket.Creator creator) {
        static final Result NONE = new Result(Outcome.NONE, null);
        static final Result CONSUMED = new Result(Outcome.CONSUMED, null);
    }

    private boolean open;
    private String query = "";
    /** The query the rows on screen answer, so a late reply to an older one can be recognised. */
    private String answered = "";
    private int ticksUntilSearch = -1;
    private boolean searching;
    private boolean unavailable;
    private List<BuilderCreatorResultsPacket.Creator> results = List.of();
    private int scroll;
    private int hoveredRow = -1;
    private boolean hoveredStar;
    private boolean hoveredClear;

    // Geometry from the last frame, so a click lands on what was drawn.
    private InventoryEditorLayout.Rect panel;
    private InventoryEditorLayout.Rect clearRect;
    private InventoryEditorLayout.Rect allRect;
    private boolean hoveredAll;
    private int rowsTop;
    private int visibleRows;

    public boolean isOpen() {
        return open;
    }

    /** Show the panel, and ask for the stars an empty box lists. */
    public void open() {
        this.open = true;
        this.hoveredRow = -1;
        BuilderProfileState.listenForCreators(this::onResults);
        // Asked once on the way in rather than per search: it is the same answer for every query
        // typed here, and a star that only appeared after the second search would look forgotten.
        // The answer lands in EditorCreatorBuilds, this screen's one sink for it.
        EditorCreatorBuilds.requestFavourites();
    }

    /** Hide the panel and stop listening. The query is kept — reopening resumes where it was. */
    public void close() {
        this.open = false;
        this.ticksUntilSearch = -1;
        this.searching = false;
        BuilderProfileState.listenForCreators(null);
    }

    /**
     * What the panel shows: the search results, or the starred builders when nothing is typed.
     *
     * <p>Everything that draws a row, indexes one or clamps the scroll goes through here — reading
     * {@link #results} directly is how a panel ends up drawing one list and clicking another.</p>
     */
    private List<BuilderCreatorResultsPacket.Creator> rows() {
        return query.trim().isEmpty() ? EditorCreatorBuilds.starredBuilders() : results;
    }

    /** The debounce. Called from the screen's own tick. */
    public void tick() {
        if (!open || ticksUntilSearch < 0) return;
        if (ticksUntilSearch-- > 0) return;
        ticksUntilSearch = -1;
        String q = query.trim();
        if (q.isEmpty()) return;
        this.searching = true;
        // The search follows whatever pool the Settings tab's Relay row points at — finding a
        // builder on one relay and listing them on the other would name somebody with nothing to see.
        DungeonTrainNet.sendToServer(new BuilderCreatorSearchPacket(q, BuilderProfileState.live()));
    }

    private void onResults(BuilderCreatorResultsPacket packet) {
        if (!packet.query().equals(query.trim())) return;
        this.searching = false;
        this.unavailable = !packet.found();
        this.answered = packet.query();
        this.results = packet.creators();
        this.scroll = 0;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /** A printable character joins the query and restarts the debounce. */
    public boolean charTyped(char c) {
        if (!open) return false;
        if (c < ' ' || c == 127 || query.length() >= MAX_QUERY) return true;
        setQuery(query + c);
        return true;
    }

    public void backspace() {
        if (!query.isEmpty()) setQuery(query.substring(0, query.length() - 1));
    }

    private void setQuery(String next) {
        this.query = next;
        this.scroll = 0;
        if (next.trim().isEmpty()) {
            // Back to the starred builders rather than to nothing, and a failed search must not
            // label the list that replaces it.
            this.ticksUntilSearch = -1;
            this.results = List.of();
            this.answered = "";
            this.searching = false;
            this.unavailable = false;
        } else {
            this.ticksUntilSearch = SEARCH_DELAY_TICKS;
        }
    }

    public boolean scrollBy(int dir) {
        if (!open) return false;
        int max = Math.max(0, rows().size() - visibleRows);
        scroll = Math.max(0, Math.min(scroll + dir, max));
        return true;
    }

    /** A click on the panel: a builder, a star, the clear row, or the panel's own background. */
    public Result mouseClicked(double mx, double my) {
        if (!open || panel == null) return Result.NONE;
        if (!panel.contains(mx, my)) return Result.NONE;   // the screen closes the panel
        if (allRect != null && allRect.contains(mx, my)) {
            return new Result(Outcome.ALL, null);
        }
        if (clearRect != null && clearRect.contains(mx, my)) {
            return new Result(Outcome.CLEARED, null);
        }
        int row = rowAt(mx, my);
        if (row < 0) return Result.CONSUMED;
        BuilderCreatorResultsPacket.Creator creator = rows().get(row);
        if (mx >= panel.right() - PAD - STAR_W) {
            toggleStar(creator);
            return Result.CONSUMED;
        }
        return new Result(Outcome.PICKED, creator);
    }

    private int rowAt(double mx, double my) {
        if (my < rowsTop || mx < panel.x() + PAD || mx >= panel.right() - PAD) return -1;
        int k = (int) ((my - rowsTop) / ROW_H);
        int idx = scroll + k;
        return k >= 0 && k < visibleRows && idx < rows().size() ? idx : -1;
    }

    /**
     * Star or un-star a builder.
     *
     * <p>Optimistic, like every other star: the glyph flips now and the packet follows. Being wrong
     * costs a stale star until the next listing; waiting on a round trip costs every press feeling
     * broken.</p>
     */
    private void toggleStar(BuilderCreatorResultsPacket.Creator creator) {
        EditorCreatorBuilds.toggleBuilderStar(creator);
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    public void render(GuiGraphics g, Font font, InventoryEditorLayout layout, EditorScreenTheme theme,
                       int mouseX, int mouseY) {
        if (!open) return;
        InventoryEditorLayout.Rect area = layout.panel();
        int w = Math.min(WIDTH, Math.max(120, area.w() - 20));
        int h = Math.min(MAX_HEIGHT, Math.max(80, area.h() - 20));
        int x = area.x() + (area.w() - w) / 2;
        int y = area.y() + (area.h() - h) / 2;
        panel = new InventoryEditorLayout.Rect(x, y, w, h);

        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, theme.outline());
        g.fill(x, y, x + w, y + h, BG);

        // Title.
        String title = EditorScreenLang.text(EditorScreenLang.CREATORS_TITLE);
        g.drawString(font, title, x + PAD, y + PAD, MenuRowPainter.TEXT_HEADER, false);

        // The query, typed straight into the panel — there is no widget here to focus or lose.
        int fieldY = y + PAD + HEADER_H;
        g.fill(x + PAD, fieldY, x + w - PAD, fieldY + FIELD_H, FIELD_BG);
        String shown = query.isEmpty()
            ? EditorScreenLang.text(EditorScreenLang.CREATORS_HINT) : query;
        int textY = fieldY + (FIELD_H - font.lineHeight) / 2 + 1;
        g.drawString(font, font.plainSubstrByWidth(shown, w - PAD * 2 - 6), x + PAD + 2, textY,
            query.isEmpty() ? 0xFF808080 : 0xFFFFFFFF, false);
        if (!query.isEmpty()) {
            g.drawString(font, "_", x + PAD + 2 + font.width(query), textY, 0xFFFFFFFF, false);
        }

        // "All builders" — everybody's uploads in one grid, above the names because it is the row
        // for the reviewer who has nobody in mind yet, which is how this panel is usually reached.
        int listTop = fieldY + FIELD_H + 2;
        allRect = null;
        if (!EditorCreatorBuilds.pooled()) {
            allRect = new InventoryEditorLayout.Rect(x + PAD, listTop, w - PAD * 2, ROW_H);
            hoveredAll = allRect.contains(mouseX, mouseY);
            g.fill(allRect.x(), allRect.y(), allRect.right(), allRect.bottom(),
                hoveredAll ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
            g.drawString(font, EditorScreenLang.text(EditorScreenLang.CREATORS_ALL),
                allRect.x() + 3, allRect.y() + (ROW_H - font.lineHeight) / 2 + 1,
                hoveredAll ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
            listTop += ROW_H + 1;
        }

        // "My builds" — the way out of somebody else's profile, offered only while in one.
        clearRect = null;
        // Whenever the browser is showing relay builds at all, pooled ones included — it is the way
        // back to this world's own templates, and the pool is not a profile with a name to test.
        if (EditorCreatorBuilds.active()) {
            clearRect = new InventoryEditorLayout.Rect(x + PAD, listTop, w - PAD * 2, ROW_H);
            hoveredClear = clearRect.contains(mouseX, mouseY);
            g.fill(clearRect.x(), clearRect.y(), clearRect.right(), clearRect.bottom(),
                hoveredClear ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
            g.drawString(font, EditorScreenLang.text(EditorScreenLang.CREATORS_MINE),
                clearRect.x() + 3, clearRect.y() + (ROW_H - font.lineHeight) / 2 + 1,
                hoveredClear ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
            listTop += ROW_H + 1;
        }

        // The rows themselves.
        rowsTop = listTop;
        int listBottom = y + h - PAD - NOTE_H;
        visibleRows = Math.max(1, (listBottom - rowsTop) / ROW_H);
        List<BuilderCreatorResultsPacket.Creator> shownRows = rows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, shownRows.size() - visibleRows)));
        hoveredRow = rowAt(mouseX, mouseY);
        hoveredStar = hoveredRow >= 0 && mouseX >= panel.right() - PAD - STAR_W;
        for (int k = 0; k < visibleRows && scroll + k < shownRows.size(); k++) {
            int idx = scroll + k;
            BuilderCreatorResultsPacket.Creator creator = shownRows.get(idx);
            int rowY = rowsTop + k * ROW_H;
            int rowX = x + PAD;
            int nameW = w - PAD * 2 - STAR_W - 1;
            boolean hovName = hoveredRow == idx && !hoveredStar;
            boolean hovStar = hoveredRow == idx && hoveredStar;
            g.fill(rowX, rowY, rowX + nameW, rowY + ROW_H - 1,
                hovName ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
            String label = EditorScreenLang.text(EditorScreenLang.CREATORS_ROW,
                creator.name(), creator.builds());
            g.drawString(font, font.plainSubstrByWidth(label, nameW - 6), rowX + 3,
                rowY + (ROW_H - font.lineHeight) / 2, hovName ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
            int starX = x + w - PAD - STAR_W;
            g.fill(starX, rowY, starX + STAR_W, rowY + ROW_H - 1,
                hovStar ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
            String star = EditorCreatorBuilds.starredBuilder(creator.uuid()) ? "★" : "☆";
            g.drawString(font, star, starX + (STAR_W - font.width(star)) / 2,
                rowY + (ROW_H - font.lineHeight) / 2, hovStar ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFDD55, false);
        }

        String note = statusNote();
        if (note != null) {
            g.drawString(font, font.plainSubstrByWidth(note, w - PAD * 2), x + PAD,
                y + h - PAD - font.lineHeight, NOTE_TEXT, false);
        }
    }

    /**
     * The line under the list. "Nobody by that name" and "this build cannot search" are different
     * answers to the same empty list — one is worth retyping for, the other never will be.
     */
    private String statusNote() {
        if (searching) return EditorScreenLang.text(EditorScreenLang.CREATORS_SEARCHING);
        if (query.trim().isEmpty()) {
            return EditorScreenLang.text(EditorCreatorBuilds.starredBuilders().isEmpty()
                ? EditorScreenLang.CREATORS_PROMPT : EditorScreenLang.CREATORS_FAVOURITES);
        }
        if (unavailable) return EditorScreenLang.text(EditorScreenLang.CREATORS_UNAVAILABLE);
        if (results.isEmpty() && !answered.isEmpty()) {
            return EditorScreenLang.text(EditorScreenLang.CREATORS_NONE);
        }
        return null;
    }
}
