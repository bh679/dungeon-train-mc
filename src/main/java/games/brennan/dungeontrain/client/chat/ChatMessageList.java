package games.brennan.dungeontrain.client.chat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

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
    private static final int INPUT_H = 16;   // single-line send box docked at the bottom
    private static final int INPUT_GAP = 4;  // gap between the message list and the input box
    private static final int MAX_SEND_CHARS = 2000; // Discord's hard limit; the relay clamps too

    private static final int BG = 0xC00E0E14;
    private static final int BORDER = 0xFF2B2B38;
    private static final int TITLE_COLOR = 0xFF8AB4F8;
    private static final int UNREAD_COLOR = 0xFFF2A33C; // amber "+N" badge for offline messages
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
    private final Set<String> knownIds = new HashSet<>(); // message ids already shown → dedupe live appends
    private Consumer<ChatHistory.Message> onSeen = m -> {};
    private Consumer<String> onSubmit = t -> {};
    private Component status = Component.translatable("gui.dungeontrain.menu_chat.loading");
    private int totalHeight;
    private int scroll;
    private int unread; // offline messages that arrived since last open (relay inbox) → "+N" title badge
    private int localSeq; // synthetic ids for optimistic outgoing echoes
    private boolean selected; // click-selected → drawn on top at full opacity; otherwise behind + faded

    /**
     * The send box. Owned + manually rendered (not a registered screen widget) so it tracks the panel's
     * raise/fade passes — it draws on top only when the panel is raised, never behind the title logo.
     */
    private final EditBox input;

    public ChatMessageList(int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable("gui.dungeontrain.menu_chat.title"));
        this.input = new EditBox(font, getX() + PAD, inputTop(), inputWidth(), INPUT_H,
                Component.translatable("gui.dungeontrain.menu_chat.input_hint"));
        this.input.setMaxLength(MAX_SEND_CHARS);
        this.input.setHint(Component.translatable("gui.dungeontrain.menu_chat.input_hint"));
        this.input.setVisible(true);  // we gate drawing ourselves; visible=true lets it consume input
        this.input.setFocused(false); // focused only while the panel is raised
    }

    public void setOnSeen(Consumer<ChatHistory.Message> onSeen) {
        this.onSeen = onSeen == null ? m -> {} : onSeen;
    }

    /** Set the count of offline messages that arrived since last open — rendered as a "+N" title badge. */
    public void setUnread(int unread) {
        this.unread = Math.max(0, unread);
    }

    /** Fired with the trimmed text when the player submits the send box (Enter). */
    public void setOnSubmit(Consumer<String> onSubmit) {
        this.onSubmit = onSubmit == null ? t -> {} : onSubmit;
    }

    /** Whether the given mouse position is over the panel. */
    public boolean isWithin(double mx, double my) {
        return mx >= getX() && mx <= getX() + width && my >= getY() && my <= getY() + height;
    }

    /** Click-selected → the panel is drawn in front at full opacity instead of behind + faded. */
    public boolean isSelected() {
        return selected;
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
        this.knownIds.clear();
        if (history == null || history.messages() == null || history.messages().isEmpty()) {
            setStatus(Component.translatable("gui.dungeontrain.menu_chat.empty"));
            return;
        }
        int wrap = contentWidth();
        // Conversation-only view: human replies, the player's dev-tagged / conversation-adjacent /
        // menu-sent chat lines, and compact survey answers — see MenuChatFilter for the full rules.
        for (ChatHistory.Message m : MenuChatFilter.filterHistory(history.messages(),
                ChatOutbox.get()::isSentByMe)) {
            entries.add(buildEntry(m, wrap));
            knownIds.add(m.id());
        }
        if (entries.isEmpty()) {
            setStatus(Component.translatable("gui.dungeontrain.menu_chat.empty")); // thread was all reports
            return;
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
        return height - TITLE_H - PAD - INPUT_H - INPUT_GAP; // reserve the bottom input row
    }

    /** Top y of the docked send box (a fixed PAD above the bottom border). */
    private int inputTop() {
        return getY() + height - PAD - INPUT_H;
    }

    private int inputWidth() {
        return Math.max(16, width - PAD * 2);
    }

    /** Whether {@code (mx,my)} is over the send box (only meaningful while the panel is raised). */
    private boolean overInput(double mx, double my) {
        return mx >= getX() + PAD && mx <= getX() + PAD + inputWidth()
                && my >= inputTop() && my <= inputTop() + INPUT_H;
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
        ChatHistory.Embed survey = MenuChatFilter.surveyEmbed(m);
        if (survey != null) {
            buildSurveyLines(e, survey, wrap);
            if (!e.lines.isEmpty()) {
                return e; // compact survey: just the "X/10" score + the comment, no author/question clutter
            }
        }
        String author = m.authorName() == null ? "?" : m.authorName();
        e.lines.add(new Line(FormattedCharSequence.forward(author, net.minecraft.network.chat.Style.EMPTY),
                m.isInbound() ? AUTHOR_INBOUND : AUTHOR_SELF, 0));

        if (m.content() != null && !m.content().isBlank()) {
            String shown = MenuChatFilter.prettifyMentions(m.content()); // "<@123…>" → "@dev"
            for (FormattedCharSequence l : font.split(Component.literal(shown), wrap)) {
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

    /**
     * Compact survey render: one labeled score line ("Recommend 9/10", "Bug — Other" — the label matched
     * from the bundled survey definitions via {@link SurveyLabels}, dropped when unknown) then the comment
     * in quotes beneath it — no "📋 Feedback" title, no question-prompt sentence.
     */
    private void buildSurveyLines(Entry e, ChatHistory.Embed embed, int wrap) {
        String label = SurveyLabels.labelFor(embed.description()); // the embed description is the prompt
        String rating = fieldValue(embed, "Rating");
        String option = rating == null ? fieldValue(embed, "Answer") : null; // multiple-choice option
        String scoreLine = null;
        if (rating != null && !rating.isBlank()) {
            String s = rating.replace(" ", ""); // "9 / 10" → "9/10"
            scoreLine = label != null ? label + " " + s : s;
        } else if (option != null && !option.isBlank()) {
            scoreLine = label != null ? label + " — " + option : option;
        }
        if (scoreLine != null) {
            for (FormattedCharSequence l : font.split(Component.literal(scoreLine), wrap)) {
                e.lines.add(new Line(l, EMBED_ACCENT, 0));
            }
        }
        String comment = commentValue(embed);
        if (comment != null && !comment.isBlank()) {
            for (FormattedCharSequence l : font.split(Component.literal("\"" + comment + "\""), wrap)) {
                e.lines.add(new Line(l, CONTENT, 0));
            }
        }
    }

    /** Value of the embed field named {@code name} (case-insensitive), or null. */
    private static String fieldValue(ChatHistory.Embed embed, String name) {
        if (embed == null || embed.fields() == null) {
            return null;
        }
        for (ChatHistory.Field f : embed.fields()) {
            if (f != null && f.name() != null && f.name().equalsIgnoreCase(name)) {
                return f.value();
            }
        }
        return null;
    }

    /** The comment field — the first field that isn't the score ("Rating"/"Answer"). */
    private static String commentValue(ChatHistory.Embed embed) {
        if (embed == null || embed.fields() == null) {
            return null;
        }
        for (ChatHistory.Field f : embed.fields()) {
            if (f == null || f.name() == null) {
                continue;
            }
            if (f.name().equalsIgnoreCase("Rating") || f.name().equalsIgnoreCase("Answer")) {
                continue;
            }
            if (f.value() != null && !f.value().isBlank()) {
                return f.value();
            }
        }
        return null;
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

    private static final float FADED_ALPHA = 0.9f; // 10% faded while not selected

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (selected) {
            return; // drawn on top at full opacity after the screen's logo/splash (see MainMenuChatPanel)
        }
        draw(g, FADED_ALPHA, false, mouseX, mouseY, partialTick);
    }

    /** Draw the panel on top of everything at full opacity — used while it's click-selected. */
    public void renderRaised(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        draw(g, 1.0f, true, mouseX, mouseY, partialTick);
    }

    private void draw(GuiGraphics g, float alpha, boolean raised, int mouseX, int mouseY, float partialTick) {
        int border = sa(BORDER, alpha);
        g.fill(getX(), getY(), getX() + width, getY() + height, sa(BG, alpha));
        g.fill(getX(), getY(), getX() + width, getY() + 1, border);
        g.fill(getX(), getY() + height - 1, getX() + width, getY() + height, border);
        g.fill(getX(), getY(), getX() + 1, getY() + height, border);
        g.fill(getX() + width - 1, getY(), getX() + width, getY() + height, border);

        Component title = Component.translatable("gui.dungeontrain.menu_chat.title");
        int titleY = getY() + (TITLE_H - font.lineHeight) / 2;
        g.drawString(font, title, getX() + PAD, titleY, sa(TITLE_COLOR, alpha), true);
        if (unread > 0) {
            Component badge = Component.translatable("gui.dungeontrain.menu_chat.unread", unread);
            g.drawString(font, badge, getX() + PAD + font.width(title) + 4, titleY, sa(UNREAD_COLOR, alpha), true);
        }
        g.fill(getX() + 1, getY() + TITLE_H - 1, getX() + width - 1, getY() + TITLE_H, border);

        int cTop = contentTop();
        int cHeight = contentHeight();
        if (status != null) {
            List<FormattedCharSequence> lines = font.split(status, contentWidth());
            int sy = cTop + Math.max(0, (cHeight - lines.size() * font.lineHeight) / 2);
            for (FormattedCharSequence l : lines) {
                g.drawString(font, l, getX() + PAD, sy, sa(STATUS, alpha), false);
                sy += font.lineHeight;
            }
        } else {
            g.enableScissor(getX() + 1, cTop, getX() + width - 1, cTop + cHeight);
            int y = cTop - scroll;
            for (Entry e : entries) {
                int entryTop = y + e.top;
                int entryBottom = entryTop + e.height();
                if (entryBottom >= cTop && entryTop <= cTop + cHeight) {
                    int ly = entryTop;
                    for (Line line : e.lines) {
                        g.drawString(font, line.seq, getX() + PAD + line.indent, ly, sa(line.color, alpha), false);
                        ly += font.lineHeight;
                    }
                    maybeMarkSeen(e, entryTop, entryBottom, cTop, cHeight);
                }
            }
            g.disableScissor();
            renderScrollbar(g, cTop, cHeight, alpha);
        }

        // The send box draws only on the raised pass — full opacity, on top of the title logo. When the
        // panel is faded/behind we leave the bottom row empty rather than show a half-lit input.
        if (raised) {
            input.render(g, mouseX, mouseY, partialTick);
        }
    }

    /** Scale a packed ARGB colour's alpha by {@code f} (drives the not-selected fade). */
    private static int sa(int argb, float f) {
        int a = Math.round(((argb >>> 24) & 0xFF) * f);
        a = Math.max(0, Math.min(255, a));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private void maybeMarkSeen(Entry e, int entryTop, int entryBottom, int cTop, int cHeight) {
        ChatHistory.Message m = e.message;
        if (m.isInbound() && !m.seen() && !reportedSeen.contains(m.id())
                && entryTop >= cTop && entryBottom <= cTop + cHeight) {
            reportedSeen.add(m.id());
            onSeen.accept(m);
        }
    }

    private void renderScrollbar(GuiGraphics g, int cTop, int cHeight, float alpha) {
        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int trackX = getX() + width - SCROLLBAR_W - 1;
        int thumbH = Math.max(12, (int) ((long) cHeight * cHeight / totalHeight));
        int thumbY = cTop + (int) ((long) (cHeight - thumbH) * scroll / max);
        g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, sa(SCROLLBAR, alpha));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.visible) {
            return false; // hidden until the dev has messaged (see MainMenuChatPanel) — swallow nothing
        }
        if (isWithin(mouseX, mouseY)) {
            if (!selected) {
                selected = true;              // first click raises and readies the send box for typing
                focusInput(true);
            } else if (overInput(mouseX, mouseY)) {
                input.mouseClicked(mouseX, mouseY, button); // place the caret within the box
                focusInput(true);
            } else {
                selected = false;             // a second click on the body sends it back
                focusInput(false);
            }
            return true;
        }
        selected = false; // click off the panel (background) → send to back
        focusInput(false);
        return false;
    }

    private void focusInput(boolean focused) {
        input.setFocused(focused);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (!selected || !input.isFocused()) {
            return super.keyPressed(key, scan, mods);
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            submitInput();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            selected = false; // Esc lowers the panel (TitleScreen has no other Esc action)
            focusInput(false);
            return true;
        }
        return input.keyPressed(key, scan, mods) || super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (selected && input.isFocused()) {
            return input.charTyped(c, mods);
        }
        return false;
    }

    private void submitInput() {
        String text = input.getValue();
        if (text == null) {
            return;
        }
        text = text.trim();
        if (text.isEmpty()) {
            return; // never send a blank line
        }
        input.setValue("");
        onSubmit.accept(text);
    }

    /**
     * Optimistically echo a just-sent message as a self-styled line and jump to it. The real message
     * reappears from Discord on the next open; if the player somehow sends before history finishes
     * loading, {@link #setHistory} replaces this entry — harmless, since the message was still queued/sent.
     */
    public void appendOutgoing(String authorName, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        this.status = null;
        String name = authorName == null || authorName.isBlank() ? "Me" : authorName;
        ChatHistory.Message m = new ChatHistory.Message(
                "local-" + (localSeq++), null, name, false, true, content,
                List.of(), List.of(), null, true); // isWebhook=true → styled as self, never marked seen
        entries.add(buildEntry(m, contentWidth()));
        knownIds.add(m.id());
        layout();
        this.scroll = maxScroll();
    }

    /**
     * Append a live inbound reply (from the relay inbox poll) if it isn't already shown. Real-person
     * replies are inbound-styled and flow through {@link #maybeMarkSeen}, so one the player sees still
     * earns its 👀. Keeps the view pinned to the bottom only when it was already there, so a player
     * scrolled up reading history isn't yanked down. Returns {@code true} if it was newly appended.
     */
    public boolean appendInbound(ChatHistory.Message m) {
        if (m == null || m.id() == null || knownIds.contains(m.id()) || MenuChatFilter.isAutomatedReport(m)) {
            return false;
        }
        boolean atBottom = scroll >= maxScroll();
        this.status = null;
        entries.add(buildEntry(m, contentWidth()));
        knownIds.add(m.id());
        layout();
        if (atBottom) {
            this.scroll = maxScroll(); // follow the conversation only if already at the bottom
        }
        return true;
    }

    /** Increment the unread badge for live arrivals — distinct from {@link #setUnread} which replaces it. */
    public void addUnread(int n) {
        if (n > 0) {
            this.unread += n;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!this.visible || status != null || maxScroll() == 0
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
