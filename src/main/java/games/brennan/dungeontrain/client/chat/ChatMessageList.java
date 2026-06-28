package games.brennan.dungeontrain.client.chat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A self-contained scrollable list that renders a player's Discord thread on the title screen: chat
 * lines, feedback-survey embeds, and bug-report attachments, each styled distinctly. No vanilla
 * scroll-list widget fit (none subclasses {@code ObjectSelectionList} here), so this is a focused
 * {@link AbstractWidget} with its own clipping + scrollbar.
 *
 * <p>Newest messages sit at the bottom (it scrolls to the latest on load). As a fully-visible inbound
 * message (a real Discord-side post, not the player's own webhook echo) that isn't already seen
 * scrolls into view, {@link #onSeen} fires once for it — the panel turns that into a 👀 reaction.</p>
 */
public final class ChatMessageList extends AbstractWidget {

    private static final int PAD = 6;
    private static final int TITLE_H = 13;
    private static final int SCROLLBAR_W = 3;
    private static final int MSG_GAP = 5;

    private static final int BG = 0xC00E0E14;
    private static final int BORDER = 0xFF2B2B38;
    private static final int TITLE_COLOR = 0xFF8AB4F8;
    private static final int AUTHOR_INBOUND = 0xFFFFFFFF;
    private static final int AUTHOR_SELF = 0xFF9BE6A0;
    private static final int CONTENT = 0xFFCBD0DA;
    private static final int EMBED_ACCENT = 0xFF8AB4F8;
    private static final int FIELD_NAME = 0xFFB7BFCC;
    private static final int ATTACH = 0xFFE0B060;
    private static final int STATUS = 0xFF8A8F99;
    private static final int SCROLLBAR = 0x80AAB0BE;

    private final Font font = Minecraft.getInstance().font;

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> reportedSeen = new HashSet<>();
    private Consumer<ChatHistory.Message> onSeen = m -> {};
    private Component status = Component.translatable("gui.dungeontrain.menu_chat.loading");
    private int totalHeight;
    private int scroll;

    public ChatMessageList(int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable("gui.dungeontrain.menu_chat.title"));
    }

    public void setOnSeen(Consumer<ChatHistory.Message> onSeen) {
        this.onSeen = onSeen == null ? m -> {} : onSeen;
    }

    /** Show a one-line status (loading / offline / empty) instead of a list. */
    public void setStatus(Component status) {
        this.status = status;
        this.entries.clear();
        this.totalHeight = 0;
        this.scroll = 0;
    }

    /** Load history, lay it out, and jump to the newest message. */
    public void setHistory(ChatHistory history) {
        this.status = null;
        this.entries.clear();
        this.reportedSeen.clear();
        if (history == null || history.messages() == null || history.messages().isEmpty()) {
            setStatus(Component.translatable("gui.dungeontrain.menu_chat.empty"));
            return;
        }
        int wrap = contentWidth();
        for (ChatHistory.Message m : history.messages()) {
            entries.add(buildEntry(m, wrap));
        }
        layout();
        this.scroll = maxScroll(); // newest at the bottom
    }

    private int contentWidth() {
        return Math.max(16, width - PAD * 2 - SCROLLBAR_W);
    }

    private int contentTop() {
        return getY() + TITLE_H;
    }

    private int contentHeight() {
        return height - TITLE_H - PAD;
    }

    private int maxScroll() {
        return Math.max(0, totalHeight - contentHeight());
    }

    private void layout() {
        int h = 0;
        for (Entry e : entries) {
            e.top = h;
            h += e.height();
            h += MSG_GAP;
        }
        totalHeight = Math.max(0, h - MSG_GAP);
    }

    // --- entry construction ---

    private Entry buildEntry(ChatHistory.Message m, int wrap) {
        Entry e = new Entry(m);
        String author = m.authorName() == null ? "?" : m.authorName();
        e.lines.add(new Line(FormattedCharSequence.forward(author, net.minecraft.network.chat.Style.EMPTY),
                m.isInbound() ? AUTHOR_INBOUND : AUTHOR_SELF, 0));

        if (m.content() != null && !m.content().isBlank()) {
            for (FormattedCharSequence l : font.split(Component.literal(m.content()), wrap)) {
                e.lines.add(new Line(l, CONTENT, 0));
            }
        }
        if (m.hasEmbeds()) {
            for (ChatHistory.Embed embed : m.embeds()) {
                addEmbed(e, embed, wrap);
            }
        }
        if (m.hasAttachments()) {
            for (ChatHistory.Attachment a : m.attachments()) {
                String name = a.filename() == null ? "attachment" : a.filename();
                for (FormattedCharSequence l : font.split(Component.literal("[file] " + name), wrap)) {
                    e.lines.add(new Line(l, ATTACH, 0));
                }
            }
        }
        return e;
    }

    private void addEmbed(Entry e, ChatHistory.Embed embed, int wrap) {
        int indent = 4;
        if (embed.title() != null && !embed.title().isBlank()) {
            for (FormattedCharSequence l : font.split(Component.literal(embed.title()), wrap - indent)) {
                e.lines.add(new Line(l, EMBED_ACCENT, indent));
            }
        }
        if (embed.description() != null && !embed.description().isBlank()) {
            for (FormattedCharSequence l : font.split(Component.literal(embed.description()), wrap - indent)) {
                e.lines.add(new Line(l, CONTENT, indent));
            }
        }
        if (embed.fields() != null) {
            for (ChatHistory.Field f : embed.fields()) {
                String name = f.name() == null ? "" : f.name();
                String value = f.value() == null ? "" : f.value();
                for (FormattedCharSequence l : font.split(Component.literal(name + ": " + value), wrap - indent)) {
                    e.lines.add(new Line(l, FIELD_NAME, indent));
                }
            }
        }
    }

    // --- rendering ---

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(getX(), getY(), getX() + width, getY() + height, BG);
        g.fill(getX(), getY(), getX() + width, getY() + 1, BORDER);
        g.fill(getX(), getY() + height - 1, getX() + width, getY() + height, BORDER);
        g.fill(getX(), getY(), getX() + 1, getY() + height, BORDER);
        g.fill(getX() + width - 1, getY(), getX() + width, getY() + height, BORDER);

        Component title = Component.translatable("gui.dungeontrain.menu_chat.title");
        g.drawString(font, title, getX() + PAD, getY() + (TITLE_H - font.lineHeight) / 2, TITLE_COLOR, true);
        g.fill(getX() + 1, getY() + TITLE_H - 1, getX() + width - 1, getY() + TITLE_H, BORDER);

        int cTop = contentTop();
        int cHeight = contentHeight();
        if (status != null) {
            List<FormattedCharSequence> lines = font.split(status, contentWidth());
            int sy = cTop + Math.max(0, (cHeight - lines.size() * font.lineHeight) / 2);
            for (FormattedCharSequence l : lines) {
                g.drawString(font, l, getX() + PAD, sy, STATUS, false);
                sy += font.lineHeight;
            }
            return;
        }

        g.enableScissor(getX() + 1, cTop, getX() + width - 1, cTop + cHeight);
        int y = cTop - scroll;
        for (Entry e : entries) {
            int entryTop = y + e.top;
            int entryBottom = entryTop + e.height();
            if (entryBottom >= cTop && entryTop <= cTop + cHeight) {
                int ly = entryTop;
                for (Line line : e.lines) {
                    g.drawString(font, line.seq, getX() + PAD + line.indent, ly, line.color, false);
                    ly += font.lineHeight;
                }
                maybeMarkSeen(e, entryTop, entryBottom, cTop, cHeight);
            }
        }
        g.disableScissor();

        renderScrollbar(g, cTop, cHeight);
    }

    private void maybeMarkSeen(Entry e, int entryTop, int entryBottom, int cTop, int cHeight) {
        ChatHistory.Message m = e.message;
        if (m.isInbound() && !m.seen() && !reportedSeen.contains(m.id())
                && entryTop >= cTop && entryBottom <= cTop + cHeight) {
            reportedSeen.add(m.id());
            onSeen.accept(m);
        }
    }

    private void renderScrollbar(GuiGraphics g, int cTop, int cHeight) {
        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int trackX = getX() + width - SCROLLBAR_W - 1;
        int thumbH = Math.max(12, (int) ((long) cHeight * cHeight / totalHeight));
        int thumbY = cTop + (int) ((long) (cHeight - thumbH) * scroll / max);
        g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, SCROLLBAR);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (status != null || maxScroll() == 0
                || mouseX < getX() || mouseX > getX() + width
                || mouseY < getY() || mouseY > getY() + height) {
            return false;
        }
        scroll = Mth.clamp(scroll - (int) (scrollY * font.lineHeight * 3), 0, maxScroll());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                Component.translatable("gui.dungeontrain.menu_chat.title"));
    }

    // --- small holders ---

    private static final class Entry {
        final ChatHistory.Message message;
        final List<Line> lines = new ArrayList<>();
        int top;

        Entry(ChatHistory.Message message) {
            this.message = message;
        }

        int height() {
            return lines.size() * Minecraft.getInstance().font.lineHeight;
        }
    }

    private record Line(FormattedCharSequence seq, int color, int indent) {}
}
