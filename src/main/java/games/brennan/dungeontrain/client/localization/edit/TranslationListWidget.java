package games.brennan.dungeontrain.client.localization.edit;

import games.brennan.dungeontrain.client.ui.ListScrollbar;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.function.Consumer;

/**
 * The scrolling list of translation units.
 *
 * <p>A focused {@link AbstractWidget} with its own clipping and scrollbar, following
 * {@code ChatMessageList} — nothing in this codebase subclasses {@code ObjectSelectionList}, and
 * a fixed-height row list over a few thousand entries wants virtualised rendering anyway (only
 * the visible rows are laid out, so the list costs the same whether it holds 20 units or 2000).
 * </p>
 *
 * <p>Three lines per row, in the order a translator reads them: the key (what to look up), the
 * English (what it means), and the current translation (what to fix). Anything shorter forces
 * the translator into the edit screen just to find out which string a row is.</p>
 *
 * <p>A fourth line appears when any visible row carries a reviewer's reply — the whole list grows
 * by a line rather than individual rows doing so, because the virtualised layout here depends on
 * every row being the same height. That is the price of showing the reply where the translator is
 * looking at what they sent, and it is worth paying: a rejection they cannot read the reason for
 * is the thing this feature exists to stop.</p>
 */
public final class TranslationListWidget extends AbstractWidget {

    private static final int PAD = 4;
    private static final int SCROLLBAR_W = ListScrollbar.WIDTH;
    private static final int ROW_LINES = 3;

    private static final int BG = 0x66000000;
    private static final int ROW_HOVER = 0x33FFFFFF;
    private static final int ROW_ALT = 0x18FFFFFF;
    private static final int KEY_COLOUR = 0xFF7F7F7F;
    private static final int SOURCE_COLOUR = 0xFFFFFFFF;
    private static final int SHIPPED_COLOUR = 0xFFA0A0A0;
    /** Green: this player has an override on the row. */
    private static final int EDITED_COLOUR = 0xFF7FDD7F;
    /** The same blue the language list's AI-fraction ring uses. */
    private static final int AI_COLOUR = 0xFF5B9BD5;
    private static final String AI_TAG = "AI";
    /** A reviewer has written back about this string — see TranslationReviewNotes. */
    private static final String NOTE_TAG = "\u25CF";
    private static final int NOTE_COLOUR = 0xFFE8A33D;
    /** This player has read the machine translation and let it stand. */
    private static final String DISMISSED_TAG = "\u2713";
    private static final int DISMISSED_COLOUR = 0xFF7F7F7F;
    /** Separates the two halves of a collapsed row's badge: how many, and how many still waiting. */
    private static final String BADGE_SEPARATOR = " \u00b7 ";

    private final Font font;
    private final Consumer<TranslationUnit> onSelect;
    private final ListScrollbar scrollbar = new ListScrollbar();

    private List<TranslationUnit> units = List.of();
    /** The overrides for the locale being EDITED, which on a dev build is not the one displayed. */
    private TranslationEdits edits = TranslationEdits.empty("");
    /** Just the relay-approved slice of the above — what the AI badge is decided against. */
    private TranslationEdits approved = TranslationEdits.empty("");
    /** Which rows this player has marked good as is; never null, defaults to "none". */
    private java.util.function.Predicate<TranslationUnit> dismissed = (u) -> false;
    /** A row's reviewer reply, or null/blank when there is none; never null itself. */
    private java.util.function.Function<TranslationUnit, String> noteText = (u) -> null;
    /**
     * What set each row stands for while the list is collapsed, or null while it is not. A row whose
     * lookup returns null is an ordinary string standing for itself — see {@link TranslationGroups}.
     */
    private java.util.function.Function<TranslationUnit, TranslationGroups.Badge> groupBadge;
    /** True while any VISIBLE row has a reply — recomputed in {@link #setUnits}. */
    private boolean showingNotes;
    private int scroll;

    public TranslationListWidget(Font font, int x, int y, int width, int height,
                                 Consumer<TranslationUnit> onSelect) {
        super(x, y, width, height, Component.translatable("gui.dungeontrain.translate.list"));
        this.font = font;
        this.onSelect = onSelect;
    }

    /** The override layer rows render against — set before {@link #setUnits} on every refresh. */
    public void setEdits(TranslationEdits newEdits) {
        this.edits = newEdits == null ? TranslationEdits.empty("") : newEdits;
    }

    /**
     * The relay-approved layer, which decides the AI badge — a string an operator has released is
     * no longer machine translation nobody has read, whatever the jar's provenance said at build
     * time. Set alongside {@link #setEdits}.
     */
    public void setApproved(TranslationEdits newApproved) {
        this.approved = newApproved == null ? TranslationEdits.empty("") : newApproved;
    }

    /**
     * The rows this player has retired as good as is. They keep their place in the list — the
     * point is that the AI badge comes off, not that the string disappears — so an unfiltered
     * browse still shows what was dismissed, marked as dismissed.
     */
    public void setDismissed(java.util.function.Predicate<TranslationUnit> predicate) {
        this.dismissed = predicate == null ? (u) -> false : predicate;
    }

    /**
     * What a reviewer replied about each row — the one mark here the player did not make. Set
     * before {@link #setUnits}, which decides from it whether the list needs its fourth line.
     */
    public void setNoteText(java.util.function.Function<TranslationUnit, String> lookup) {
        this.noteText = lookup == null ? (u) -> null : lookup;
    }

    /**
     * Turn the collapsed-row badge on (a lookup) or off (null).
     *
     * <p>A lookup rather than a field on the row, because the counts move under the list: marking a
     * variation good as is has to take it off its set's tally without the row it was counted on
     * being rebuilt.</p>
     */
    public void setGroupBadge(
        java.util.function.Function<TranslationUnit, TranslationGroups.Badge> lookup) {
        this.groupBadge = lookup;
    }

    /** Replace the visible rows, keeping the scroll position where it still makes sense. */
    public void setUnits(List<TranslationUnit> newUnits) {
        this.units = newUnits == null ? List.of() : newUnits;
        this.showingNotes = false;
        for (TranslationUnit unit : units) {
            if (hasNote(unit)) {
                this.showingNotes = true;
                break;
            }
        }
        this.scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private boolean hasNote(TranslationUnit unit) {
        String note = noteText.apply(unit);
        return note != null && !note.isBlank();
    }

    public int rowCount() {
        return units.size();
    }

    private int rowHeight() {
        return font.lineHeight * (showingNotes ? ROW_LINES + 1 : ROW_LINES) + PAD * 2;
    }

    private int totalHeight() {
        return units.size() * rowHeight();
    }

    private int maxScroll() {
        return Math.max(0, totalHeight() - height);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(getX(), getY(), getX() + width, getY() + height, BG);
        if (units.isEmpty()) {
            Component empty = Component.translatable("gui.dungeontrain.translate.empty");
            g.drawCenteredString(font, empty, getX() + width / 2,
                getY() + height / 2 - font.lineHeight / 2, SHIPPED_COLOUR);
            return;
        }

        int rowH = rowHeight();
        int textWidth = width - PAD * 2 - SCROLLBAR_W - 2;
        g.enableScissor(getX(), getY(), getX() + width, getY() + height);
        // Only the rows intersecting the viewport are laid out — the list can hold every key in
        // every namespace plus every book field without the render cost tracking that.
        int first = Math.max(0, scroll / rowH);
        int last = Math.min(units.size() - 1, (scroll + height) / rowH);
        for (int i = first; i <= last; i++) {
            int rowY = getY() + i * rowH - scroll;
            renderRow(g, units.get(i), i, rowY, rowH, textWidth, mouseX, mouseY);
        }
        g.disableScissor();
        scrollbar.render(g, getX(), getY(), width, height, totalHeight(), scroll, maxScroll());
    }

    private void renderRow(GuiGraphics g, TranslationUnit unit, int index, int rowY, int rowH,
                           int textWidth, int mouseX, int mouseY) {
        boolean hovered = isMouseOver(mouseX, mouseY)
            && mouseY >= rowY && mouseY < rowY + rowH;
        if (hovered) {
            g.fill(getX(), rowY, getX() + width - SCROLLBAR_W - 1, rowY + rowH, ROW_HOVER);
        } else if ((index & 1) == 1) {
            g.fill(getX(), rowY, getX() + width - SCROLLBAR_W - 1, rowY + rowH, ROW_ALT);
        }

        String override = unit.type() == TranslationUnit.Type.BOOK
            ? edits.books().get(unit.id()) : edits.lang().get(unit.id());
        boolean edited = override != null;

        int textX = getX() + PAD;
        int lineY = rowY + PAD;

        // Line 1: the key, plus the row's badges right-aligned so the eye can scan a column of
        // them. Right to left, in the order they matter: a reviewer's reply is the one thing here
        // somebody is waiting on the player for, so it sits outermost.
        // The set this row stands for, outside the per-string marks: it is a statement about
        // several strings, and reading it as a badge on this one would be wrong. Measured before
        // the key is drawn, because the key has to give it the room rather than run under it.
        String badge = badgeText(unit);
        int tagRoom = 32 + (badge == null ? 0 : font.width(badge) + PAD);
        g.drawString(font, font.plainSubstrByWidth(unit.label(), Math.max(0, textWidth - tagRoom)),
            textX, lineY, KEY_COLOUR, false);
        int tagX = getX() + width - SCROLLBAR_W - 3;
        if (badge != null) {
            tagX -= font.width(badge);
            g.drawString(font, badge, tagX, lineY, badgeColour(unit), false);
            tagX -= PAD;
        }
        if (hasNote(unit)) {
            tagX -= font.width(NOTE_TAG);
            g.drawString(font, NOTE_TAG, tagX, lineY, NOTE_COLOUR, false);
            tagX -= PAD;
        }
        if (dismissed.test(unit)) {
            // Not "AI" any more: this player has read it and let it stand, which is the whole
            // point of the mark — the queue must stop offering it.
            tagX -= font.width(DISMISSED_TAG);
            g.drawString(font, DISMISSED_TAG, tagX, lineY, DISMISSED_COLOUR, false);
        } else if (TranslationFilters.needsHuman(unit, approved)) {
            tagX -= font.width(AI_TAG);
            g.drawString(font, AI_TAG, tagX, lineY, AI_COLOUR, false);
        }
        lineY += font.lineHeight;

        // Line 2: the English source (or a note when the mod ships none to compare against).
        String source = unit.source().isEmpty()
            ? Component.translatable("gui.dungeontrain.translate.no_source").getString()
            : unit.source();
        g.drawString(font, oneLine(source, textWidth), textX, lineY,
            unit.source().isEmpty() ? SHIPPED_COLOUR : SOURCE_COLOUR, false);
        lineY += font.lineHeight;

        // Line 3: what the player currently sees — their override if they have one.
        String current = edited ? override : unit.shipped();
        g.drawString(font, oneLine(current, textWidth), textX, lineY,
            edited ? EDITED_COLOUR : SHIPPED_COLOUR, false);

        // Line 4: what a reviewer said about it. Only ever present while the list is showing
        // notes at all, so the row heights this layout assumes stay uniform.
        if (!showingNotes) {
            return;
        }
        lineY += font.lineHeight;
        String note = noteText.apply(unit);
        if (note != null && !note.isBlank()) {
            g.drawString(font, oneLine(NOTE_TAG + " " + note, textWidth), textX, lineY,
                NOTE_COLOUR, false);
        }
    }

    /**
     * {@code ×13 · 8 need review} for a collapsed row, or null for a row standing for itself.
     * The second half is dropped once the set is done — a tally of zero is not news.
     */
    private String badgeText(TranslationUnit unit) {
        TranslationGroups.Badge badge = groupBadge == null ? null : groupBadge.apply(unit);
        if (badge == null || badge.size() < 2) {
            return null;
        }
        String count = Component.translatable(
            "gui.dungeontrain.translate.group.count", badge.size()).getString();
        return badge.needingReview() == 0 ? count
            : count + BADGE_SEPARATOR + Component.translatable(
                "gui.dungeontrain.translate.group.needs", badge.needingReview()).getString();
    }

    /** Blue while the set has work left in it, the same blue the AI tag uses; grey once it has not. */
    private int badgeColour(TranslationUnit unit) {
        TranslationGroups.Badge badge = groupBadge == null ? null : groupBadge.apply(unit);
        return badge != null && badge.needingReview() > 0 ? AI_COLOUR : SHIPPED_COLOUR;
    }

    /** Collapse newlines so a multi-paragraph book variant still occupies exactly one line. */
    private String oneLine(String text, int maxWidth) {
        return font.plainSubstrByWidth(text.replace('\n', ' ').replace('\r', ' '), maxWidth);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active || button != 0 || !isMouseOver(mouseX, mouseY) || units.isEmpty()) {
            return false;
        }
        // The bar first, and only where there is one: a press on the track is aimed at the
        // scrollbar, not at the row it happens to be drawn over.
        if (maxScroll() > 0 && scrollbar.isOverTrack(mouseX, getX(), width)) {
            scrollbar.begin();
            scroll = scrollbar.scrollFor(mouseY, getY(), height, totalHeight(), maxScroll());
            return true;
        }
        int index = (int) ((mouseY - getY() + scroll) / rowHeight());
        if (index < 0 || index >= units.size()) {
            return false;
        }
        playDownSound(Minecraft.getInstance().getSoundManager());
        onSelect.accept(units.get(index));
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX,
                                double dragY) {
        if (!scrollbar.isDragging()) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        scroll = scrollbar.scrollFor(mouseY, getY(), height, totalHeight(), maxScroll());
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        scrollbar.end();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible || !isMouseOver(mouseX, mouseY) || maxScroll() == 0) {
            return false;
        }
        scroll = Mth.clamp(scroll - (int) (scrollY * font.lineHeight * 3), 0, maxScroll());
        return true;
    }

    /** Where the list is scrolled to, so the screen can put it back after a rebuild. */
    public int scrollOffset() {
        return scroll;
    }

    /** Restore a scroll position captured before a rebuild; clamped to what the list now holds. */
    public void setScrollOffset(int offset) {
        this.scroll = Mth.clamp(offset, 0, maxScroll());
    }

    /** Scroll so {@code index} is visible — used when jumping to the next unreviewed unit. */
    public void scrollTo(int index) {
        if (index < 0 || index >= units.size()) {
            return;
        }
        scroll = Mth.clamp(index * rowHeight() - height / 2, 0, maxScroll());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
            Component.translatable("gui.dungeontrain.translate.list.narration", units.size()));
    }
}
