package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Shows the text in the edit box the way the game will: on a book page, on a button, or in chat.
 *
 * <p>Always the TYPED text, saved or not — the point is to look before committing to it. Done goes
 * back to the edit screen, which keeps what was typed (see {@code TranslationEditScreen#typed}).</p>
 *
 * <p>The book is drawn at the player's own GUI scale, exactly like a held book. The button and chat
 * previews are drawn inside a frame standing for the smallest screen Minecraft is designed for —
 * the default 854×480 window at GUI scale Auto, i.e. 427×240 GUI pixels at scale 2 — and drawn at
 * that scale's real pixel size whatever scale the player uses, because a label that fits at scale 1
 * on a big monitor is no evidence it fits there.</p>
 */
public final class TranslationPreviewScreen extends Screen {

    private static final int MARGIN = 16;
    private static final int ROW_H = 20;
    private static final int GAP = 4;
    private static final int TOP = 24;
    private static final int LABEL_COLOUR = 0xFFA0A0A0;
    private static final int GOOD_COLOUR = 0xFF7FD87F;
    private static final int BAD_COLOUR = 0xFFDD7F7F;

    /** Vanilla's written-book texture and page geometry ({@code BookViewScreen}). */
    private static final ResourceLocation BOOK_TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/gui/book.png");
    private static final int BOOK_SIZE = 192;
    private static final int BOOK_TEXT_X = 36;
    private static final int BOOK_TEXT_Y = 32;
    private static final int BOOK_TEXT_WIDTH = 114;
    private static final int BOOK_LINES = 128 / 9;

    /** The two widths a label commonly gets when this install has never seen its button. */
    private static final int[] DEFAULT_BUTTON_WIDTHS = {200, 150};

    /** The edited string in a full-context page: blue ink on the paper, so it stands out but reads. */
    private static final int HIGHLIGHT_INK = 0xFF1F4FC0;
    private static final int COVER_COLOUR = 0xFFFFFFFF;
    private static final int COVER_HIGHLIGHT = 0xFFFFFF55;

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Screen parent;
    private final TranslationPreviewKind kind;
    /** The typed text with its placeholders filled in — what a player would actually read. */
    private final String text;
    /** The real width of this string's button, or -1 when it has never been seen on one. */
    private final int knownButtonWidth;
    private final TranslationUnit unit;
    private final String locale;
    /** The typed text as typed — full context fills its placeholders itself, alongside its siblings. */
    private final String typed;
    /** Which wider page or book this string belongs to; NONE hides the Full Context button. */
    private final BookPreviewContext.Source source;

    private boolean fullContext;
    /** The frame fills the window; a click or Esc goes back. */
    private boolean fullscreen;
    /** The screen a button label was last seen on, for its full context; null if never seen. */
    private final ButtonScreenLayouts.Layout buttonLayout;
    /** This locale's lang values, for the other buttons on {@link #buttonLayout}. */
    private Map<String, String> contextLang = Map.of();
    /** Rolls the parts of the full context picked at random; Refresh changes it. */
    private long seed;
    /** The full context being shown, or null in the just-this-line view. */
    private BookPreviewContext.Preview context;
    private List<BookPreviewContext.Page> pages = List.of();
    private int page;
    private PageButton forward;
    private PageButton back;
    /** Lines of the current page the book has no room for; set as the page is drawn. */
    private int cutLines;
    private final List<Button> sampleButtons = new ArrayList<>();
    private final List<Component> readout = new ArrayList<>();

    public TranslationPreviewScreen(Screen parent, TranslationPreviewKind kind, String text,
                                    int knownButtonWidth, TranslationUnit unit, String locale,
                                    String typed) {
        super(Component.translatable(kind.buttonKey()));
        this.parent = parent;
        this.kind = kind;
        this.text = text == null ? "" : text;
        this.knownButtonWidth = knownButtonWidth;
        this.unit = unit;
        this.locale = locale;
        this.typed = typed == null ? "" : typed;
        this.source = kind == TranslationPreviewKind.BOOK
            ? BookPreviewContext.sourceOf(unit) : BookPreviewContext.Source.NONE;
        this.buttonLayout = kind == TranslationPreviewKind.BUTTON && unit != null
            ? ButtonScreenLayouts.layoutFor(unit.id()) : null;
    }

    @Override
    protected void init() {
        readout.clear();
        sampleButtons.clear();
        switch (kind) {
            case BOOK -> initBook();
            case BUTTON -> initButtons();
            case CHAT -> initChat();
            default -> { }
        }
        initBottomRow();
    }

    /** Done, and when there is wider context the Full Context toggle and (when rolled) Refresh. */
    private void initBottomRow() {
        List<Button> row = new ArrayList<>();
        if (source != BookPreviewContext.Source.NONE || buttonLayout != null) {
            Button toggle = Button.builder(Component.translatable(fullContext
                    ? "gui.dungeontrain.translate.preview.just_this"
                    : "gui.dungeontrain.translate.preview.full_context"), b -> {
                    fullContext = !fullContext;
                    page = 0;
                    rebuildWidgets();
                }).build();
            toggle.setTooltip(Tooltip.create(Component.translatable(kind == TranslationPreviewKind.BUTTON
                ? "gui.dungeontrain.translate.preview.full_context.button_tip"
                : "gui.dungeontrain.translate.preview.full_context.tip")));
            row.add(toggle);
            if (context != null && context.rolled()) {
                Button refresh = Button.builder(
                    Component.translatable("gui.dungeontrain.translate.preview.refresh"), b -> {
                        seed = ThreadLocalRandom.current().nextLong();
                        rebuildWidgets();
                    }).build();
                refresh.setTooltip(Tooltip.create(
                    Component.translatable("gui.dungeontrain.translate.preview.refresh.tip")));
                row.add(refresh);
            }
        }
        row.add(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).build());

        int each = Math.min(200, (width - MARGIN * 2 - GAP * (row.size() - 1)) / row.size());
        int x = (width - (each * row.size() + GAP * (row.size() - 1))) / 2;
        for (Button button : row) {
            button.setRectangle(each, ROW_H, x, height - MARGIN - ROW_H);
            addRenderableWidget(button);
            x += each + GAP;
        }
    }

    // ---- book -------------------------------------------------------------------------------

    private void initBook() {
        context = fullContext ? buildContext() : null;
        if (context != null) {
            pages = context.pages();
        } else {
            // The paginator the game uses for this string, so a page break lands where it will.
            pages = BookPreviewContext.justThisPages(unit, text).stream()
                .map(BookPreviewContext.Page::plain).toList();
        }
        page = Math.min(page, pages.size() - 1);
        int left = (width - BOOK_SIZE) / 2;
        int top = bookTop();
        forward = addRenderableWidget(new PageButton(left + 116, top + 157, true, b -> turn(1), true));
        back = addRenderableWidget(new PageButton(left + 43, top + 157, false, b -> turn(-1), true));
        updatePageButtons();
    }

    /**
     * The whole page or book around the typed text, or null to fall back to just the line — a
     * broken sibling must cost the translator the context, never the preview.
     */
    private BookPreviewContext.Preview buildContext() {
        try {
            return switch (source) {
                case STAT_BOOK -> BookPreviewContext.statBook(unit.id(), typed,
                    BookPreviewSiblings.lang(locale, BookPreviewContext.STAT_BOOK_PREFIX),
                    minecraft.getUser().getName(), seed);
                case LEADERBOARD -> BookPreviewSiblings.leaderboard(locale, unit.id(), typed, seed);
                case NARRATIVE -> BookPreviewContext.narrative(unit.bookPath(), unit.bookField(), typed,
                    BookPreviewSiblings.bookFields(locale, unit.bookPath()), seed);
                case DEATH_LORE -> BookPreviewContext.deathLore(unit.bookField(), typed,
                    BookPreviewSiblings.bookFields(locale, unit.bookPath()));
                default -> null;
            };
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] translation preview: no full context for {}: {}",
                unit == null ? "?" : unit.id(), e.toString());
            return null;
        }
    }

    private int bookTop() {
        return Math.max(2, Math.min(TOP, height - MARGIN - ROW_H - GAP - BOOK_SIZE));
    }

    private void turn(int delta) {
        page = Math.max(0, Math.min(pages.size() - 1, page + delta));
        updatePageButtons();
    }

    private void updatePageButtons() {
        forward.visible = page < pages.size() - 1;
        back.visible = page > 0;
    }

    private void renderBook(GuiGraphics g) {
        int left = (width - BOOK_SIZE) / 2;
        int top = bookTop();
        g.blit(BOOK_TEXTURE, left, top, 0, 0, BOOK_SIZE, BOOK_SIZE);

        Component indicator = Component.translatable("book.pageIndicator", page + 1, pages.size());
        g.drawString(font, indicator, left - font.width(indicator) + BOOK_SIZE - 44, top + 16, 0, false);

        List<FormattedCharSequence> lines = new ArrayList<>();
        List<Boolean> highlighted = new ArrayList<>();
        List<BookPreviewContext.Segment> segments = pages.get(page).segments();
        for (int s = 0; s < segments.size(); s++) {
            if (s > 0) {
                // The blank line the game's "\n\n" join leaves between parts of a page.
                lines.add(FormattedCharSequence.EMPTY);
                highlighted.add(false);
            }
            for (FormattedCharSequence line
                : font.split(FormattedText.of(segments.get(s).text()), BOOK_TEXT_WIDTH)) {
                lines.add(line);
                highlighted.add(segments.get(s).highlighted());
            }
        }
        int shown = Math.min(lines.size(), BOOK_LINES);
        for (int i = 0; i < shown; i++) {
            g.drawString(font, lines.get(i), left + BOOK_TEXT_X, top + BOOK_TEXT_Y + i * 9,
                highlighted.get(i) ? HIGHLIGHT_INK : 0, false);
        }
        cutLines = Math.max(0, lines.size() - BOOK_LINES);
    }

    /**
     * The title and "by" line above the book — where the game shows them, on the item, rather than
     * on a page — with the edited one in yellow. Skipped when the book reaches the top of the window.
     */
    private void renderCover(GuiGraphics g) {
        if (context == null || context.cover() == null || bookTop() < 2 * (font.lineHeight + 1)) {
            return;
        }
        BookPreviewContext.Cover cover = context.cover();
        g.drawCenteredString(font, cover.title(), width / 2, 2,
            cover.titleHighlighted() ? COVER_HIGHLIGHT : COVER_COLOUR);
        g.drawCenteredString(font, Component.translatable("book.byAuthor", cover.author()), width / 2,
            3 + font.lineHeight, cover.authorHighlighted() ? COVER_HIGHLIGHT : LABEL_COLOUR);
    }

    /**
     * The one line under the book: a warning when the page is fuller than the book can show (the
     * rest is simply never drawn), else the title. Skipped on a window too short to fit it.
     */
    private void renderBookCaption(GuiGraphics g) {
        int y = bookTop() + BOOK_SIZE + 2;
        if (y + font.lineHeight > height - MARGIN - ROW_H - 2) {
            return;
        }
        if (cutLines > 0) {
            drawCentered(g, Component.translatable("gui.dungeontrain.translate.preview.book.cut",
                cutLines), y, BAD_COLOUR);
        } else if (context != null && context.deathScreen()) {
            drawCentered(g, Component.translatable("gui.dungeontrain.translate.preview.book.death_screen"),
                y, LABEL_COLOUR);
        } else {
            drawCentered(g, title, y, LABEL_COLOUR);
        }
    }

    // ---- button -----------------------------------------------------------------------------

    private void initButtons() {
        if (buttonContext()) {
            initButtonContext();
            return;
        }
        int[] widths = knownButtonWidth > 0 ? new int[] {knownButtonWidth} : DEFAULT_BUTTON_WIDTHS;
        int stackHeight = widths.length * ROW_H + (widths.length - 1) * GAP * 2;
        int y = (TranslationPreviewText.SMALL_SCREEN_HEIGHT - stackHeight) / 2;
        for (int w : widths) {
            // Laid out in FRAME coordinates and drawn by hand inside the scaled frame, never added
            // as a widget: it is a picture of a button, and must not be clickable.
            sampleButtons.add(Button.builder(Component.literal(text), b -> { })
                .bounds((TranslationPreviewText.SMALL_SCREEN_WIDTH - w) / 2, y, w, ROW_H).build());
            y += ROW_H + GAP * 2;
            readout.add(fitLine(w));
        }
        if (knownButtonWidth <= 0) {
            readout.add(Component.translatable("gui.dungeontrain.translate.preview.button.unknown")
                .withColor(LABEL_COLOUR));
        }
    }

    /** Full context: the screen this label was last seen on, read out for each button it labels. */
    private void initButtonContext() {
        contextLang = BookPreviewSiblings.lang(locale, "");
        buttonLayout.widgets().stream()
            .filter(w -> w.button() && unit.id().equals(w.key()))
            .mapToInt(ButtonScreenLayouts.Widget::w).distinct().sorted()
            .forEach(w -> readout.add(fitLine(w)));
    }

    private Component fitLine(int buttonWidth) {
        int overflow = TranslationPreviewText.buttonOverflow(font.width(text), buttonWidth);
        return overflow <= 0
            ? Component.translatable("gui.dungeontrain.translate.preview.button.fits", buttonWidth)
                .withColor(GOOD_COLOUR)
            : Component.translatable("gui.dungeontrain.translate.preview.button.overflow",
                buttonWidth, overflow).withColor(BAD_COLOUR);
    }

    /** Whether the button preview is showing the recorded screen rather than sample buttons. */
    private boolean buttonContext() {
        return kind == TranslationPreviewKind.BUTTON && fullContext && buttonLayout != null;
    }

    // ---- chat -------------------------------------------------------------------------------

    private List<FormattedCharSequence> chatLines() {
        return ComponentRenderUtils.wrapComponents(Component.literal(text),
            TranslationPreviewText.CHAT_WIDTH, font);
    }

    private void initChat() {
        int lines = chatLines().size();
        // "Lines in chat: N" rather than a sentence, so no locale needs plural forms for it.
        readout.add(Component.translatable("gui.dungeontrain.translate.preview.chat.lines", lines)
            .withColor(LABEL_COLOUR));
    }

    /** Drawn the way {@code ChatComponent} draws a line at default settings, above the hotbar. */
    private void renderChat(GuiGraphics g) {
        List<FormattedCharSequence> lines = chatLines();
        int bottom = TranslationPreviewText.SMALL_SCREEN_HEIGHT - 40;
        for (int i = lines.size() - 1; i >= 0; i--) {
            int lineTop = bottom - 9;
            g.fill(0, lineTop, TranslationPreviewText.CHAT_WIDTH + 8, bottom, 0x80000000);
            g.drawString(font, lines.get(i), 4, lineTop + 1, 0xFFFFFFFF);
            bottom = lineTop;
        }
        // Where the hotbar sits, so the chat has the context it is read in.
        int hotbarLeft = (TranslationPreviewText.SMALL_SCREEN_WIDTH - 182) / 2;
        g.fill(hotbarLeft, TranslationPreviewText.SMALL_SCREEN_HEIGHT - 22,
            hotbarLeft + 182, TranslationPreviewText.SMALL_SCREEN_HEIGHT, 0x60000000);
    }

    // ---- the frame --------------------------------------------------------------------------

    /** The frame's size in its own GUI pixels: the recorded screen, or the smallest default one. */
    private int frameWidth() {
        return buttonContext() ? buttonLayout.width() : TranslationPreviewText.SMALL_SCREEN_WIDTH;
    }

    private int frameHeight() {
        return buttonContext() ? buttonLayout.height() : TranslationPreviewText.SMALL_SCREEN_HEIGHT;
    }

    /** The scale the frame's pixels were drawn at: the recorded screen's, or GUI scale Auto's 2. */
    private float actualScale() {
        int scale = buttonContext() ? Math.max(1, buttonLayout.scale()) : TranslationPreviewText.SMALL_SCREEN_GUI_SCALE;
        return (float) (scale / minecraft.getWindow().getGuiScale());
    }

    /**
     * How much to scale the frame so one of its pixels is the size it really was on this window —
     * or less, when this window cannot fit it at that size. Fullscreen fills the window either way.
     */
    private float frameScale() {
        float fitWide = (float) (width - MARGIN * 2) / frameWidth();
        if (fullscreen) {
            return Math.min(fitWide, (float) (height - MARGIN * 2) / frameHeight());
        }
        float fitHigh = (float) frameRoom() / frameHeight();
        return Math.min(actualScale(), Math.min(fitWide, fitHigh));
    }

    private boolean frameShrunk() {
        return frameScale() < actualScale() - 0.001f;
    }

    /** Readout lines wrapped to the window, so a long one is never cut off at the edges. */
    private List<FormattedCharSequence> readoutLines() {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : readout) {
            lines.addAll(font.split(line, width - MARGIN * 2));
        }
        lines.addAll(font.split(Component.translatable("gui.dungeontrain.translate.preview.frame.click")
            .withColor(LABEL_COLOUR), width - MARGIN * 2));
        return lines;
    }

    /** Vertical room for the frame: below the caption, above the readout and the Done row. */
    private int frameRoom() {
        int readoutHeight = (readoutLines().size() + 1) * (font.lineHeight + 2);
        return height - TOP - font.lineHeight - GAP - readoutHeight - MARGIN - ROW_H - GAP;
    }

    /** The frame's place on this window, as {x, y, w, h}. */
    private int[] frameRect() {
        float scale = frameScale();
        int frameW = Math.round(frameWidth() * scale);
        int frameH = Math.round(frameHeight() * scale);
        int frameY = fullscreen ? (height - frameH) / 2 : TOP + font.lineHeight + GAP;
        return new int[] {(width - frameW) / 2, frameY, frameW, frameH};
    }

    private void renderFrame(GuiGraphics g) {
        float scale = frameScale();
        int[] r = frameRect();
        int frameX = r[0], frameY = r[1], frameW = r[2], frameH = r[3];

        if (!fullscreen) {
            Component caption = buttonContext()
                ? Component.translatable("gui.dungeontrain.translate.preview.frame.screen", buttonLayout.title())
                : Component.translatable(frameShrunk()
                    ? "gui.dungeontrain.translate.preview.frame.shrunk"
                    : "gui.dungeontrain.translate.preview.frame");
            drawCentered(g, caption, TOP, LABEL_COLOUR);
        }

        g.fill(frameX - 1, frameY - 1, frameX + frameW + 1, frameY + frameH + 1, 0xFF808080);
        g.fill(frameX, frameY, frameX + frameW, frameY + frameH, 0xFF1E2530);

        // Clipped in screen space, before the transform, so nothing drawn inside can spill out.
        g.enableScissor(frameX, frameY, frameX + frameW, frameY + frameH);
        g.pose().pushPose();
        g.pose().translate(frameX, frameY, 0);
        g.pose().scale(scale, scale, 1);
        if (kind == TranslationPreviewKind.CHAT) {
            renderChat(g);
        } else if (buttonContext()) {
            ButtonLayoutRenderer.render(g, font, buttonLayout, unit.id(), text, contextLang);
        } else {
            for (Button sample : sampleButtons) {
                // Off-screen mouse: never hovered, so it shows the resting look players see.
                sample.render(g, -1, -1, 0);
            }
        }
        g.pose().popPose();
        g.disableScissor();

        if (fullscreen) {
            return;
        }
        int y = frameY + frameH + GAP;
        for (FormattedCharSequence line : readoutLines()) {
            g.drawCenteredString(font, line, width / 2, y, LABEL_COLOUR);
            y += font.lineHeight + 2;
        }
    }

    private void drawCentered(GuiGraphics g, Component line, int y, int colour) {
        g.drawCenteredString(font, line, width / 2, y, colour);
    }

    // ---- fullscreen -------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (fullscreen) {
            fullscreen = false;
            return true;
        }
        if (kind != TranslationPreviewKind.BOOK) {
            int[] r = frameRect();
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                fullscreen = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (fullscreen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            fullscreen = false;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        InWorldBackdrop.render(this, g, () -> super.renderBackground(g, mouseX, mouseY, partialTick));
        // Drawn with the background so the page-turn buttons sit on top of the book, as in vanilla.
        if (kind == TranslationPreviewKind.BOOK) {
            renderBook(g);
        } else {
            renderFrame(g);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (fullscreen) {
            // Only the frame: no buttons to hit, and a click anywhere goes back.
            renderBackground(g, mouseX, mouseY, partialTick);
            return;
        }
        super.render(g, mouseX, mouseY, partialTick);
        if (kind == TranslationPreviewKind.BOOK) {
            renderBookCaption(g);
            renderCover(g);
        } else {
            g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
