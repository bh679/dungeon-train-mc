package games.brennan.dungeontrain.client.localization.edit;

import games.brennan.dungeontrain.narrative.BookFactory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

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

    private final Screen parent;
    private final TranslationPreviewKind kind;
    /** The typed text with its placeholders filled in — what a player would actually read. */
    private final String text;
    /** The real width of this string's button, or -1 when it has never been seen on one. */
    private final int knownButtonWidth;

    private List<String> pages = List.of();
    private int page;
    private PageButton forward;
    private PageButton back;
    /** Lines of the current page the book has no room for; set as the page is drawn. */
    private int cutLines;
    private final List<Button> sampleButtons = new ArrayList<>();
    private final List<Component> readout = new ArrayList<>();

    public TranslationPreviewScreen(Screen parent, TranslationPreviewKind kind, String text,
                                    int knownButtonWidth) {
        super(Component.translatable(kind.buttonKey()));
        this.parent = parent;
        this.kind = kind;
        this.text = text == null ? "" : text;
        this.knownButtonWidth = knownButtonWidth;
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
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(width / 2 - 100, height - MARGIN - ROW_H, 200, ROW_H).build());
    }

    // ---- book -------------------------------------------------------------------------------

    private void initBook() {
        // The same paginator the narrative books go through, so a page break lands where it will.
        pages = BookFactory.paginate(text);
        if (pages.isEmpty()) {
            pages = List.of("");
        }
        page = Math.min(page, pages.size() - 1);
        int left = (width - BOOK_SIZE) / 2;
        int top = bookTop();
        forward = addRenderableWidget(new PageButton(left + 116, top + 157, true, b -> turn(1), true));
        back = addRenderableWidget(new PageButton(left + 43, top + 157, false, b -> turn(-1), true));
        updatePageButtons();
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

        List<FormattedCharSequence> lines =
            font.split(FormattedText.of(pages.get(page)), BOOK_TEXT_WIDTH);
        int shown = Math.min(lines.size(), BOOK_LINES);
        for (int i = 0; i < shown; i++) {
            g.drawString(font, lines.get(i), left + BOOK_TEXT_X, top + BOOK_TEXT_Y + i * 9, 0, false);
        }
        cutLines = Math.max(0, lines.size() - BOOK_LINES);
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
        } else {
            drawCentered(g, title, y, LABEL_COLOUR);
        }
    }

    // ---- button -----------------------------------------------------------------------------

    private void initButtons() {
        int[] widths = knownButtonWidth > 0 ? new int[] {knownButtonWidth} : DEFAULT_BUTTON_WIDTHS;
        int stackHeight = widths.length * ROW_H + (widths.length - 1) * GAP * 2;
        int y = (TranslationPreviewText.SMALL_SCREEN_HEIGHT - stackHeight) / 2;
        int textWidth = font.width(text);
        for (int w : widths) {
            // Laid out in FRAME coordinates and drawn by hand inside the scaled frame, never added
            // as a widget: it is a picture of a button, and must not be clickable.
            sampleButtons.add(Button.builder(Component.literal(text), b -> { })
                .bounds((TranslationPreviewText.SMALL_SCREEN_WIDTH - w) / 2, y, w, ROW_H).build());
            y += ROW_H + GAP * 2;
            int overflow = TranslationPreviewText.buttonOverflow(textWidth, w);
            readout.add(overflow <= 0
                ? Component.translatable("gui.dungeontrain.translate.preview.button.fits", w)
                    .withColor(GOOD_COLOUR)
                : Component.translatable("gui.dungeontrain.translate.preview.button.overflow",
                    w, overflow).withColor(BAD_COLOUR));
        }
        if (knownButtonWidth <= 0) {
            readout.add(Component.translatable("gui.dungeontrain.translate.preview.button.unknown")
                .withColor(LABEL_COLOUR));
        }
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

    // ---- the small-screen frame -------------------------------------------------------------

    /**
     * How much to scale the 427×240 frame so one of its pixels is a GUI-scale-2 pixel on this
     * window — or less, when this window cannot fit it at that size.
     */
    private float frameScale() {
        double guiScale = minecraft.getWindow().getGuiScale();
        float actual = (float) (TranslationPreviewText.SMALL_SCREEN_GUI_SCALE / guiScale);
        float fitWide = (float) (width - MARGIN * 2) / TranslationPreviewText.SMALL_SCREEN_WIDTH;
        float fitHigh = (float) frameRoom() / TranslationPreviewText.SMALL_SCREEN_HEIGHT;
        return Math.min(actual, Math.min(fitWide, fitHigh));
    }

    private boolean frameShrunk() {
        double guiScale = minecraft.getWindow().getGuiScale();
        return frameScale() < (float) (TranslationPreviewText.SMALL_SCREEN_GUI_SCALE / guiScale) - 0.001f;
    }

    /** Vertical room for the frame: below the caption, above the readout and the Done row. */
    private int frameRoom() {
        int readoutHeight = (readout.size() + 1) * (font.lineHeight + 2);
        return height - TOP - font.lineHeight - GAP - readoutHeight - MARGIN - ROW_H - GAP;
    }

    private void renderFrame(GuiGraphics g) {
        float scale = frameScale();
        int frameW = Math.round(TranslationPreviewText.SMALL_SCREEN_WIDTH * scale);
        int frameH = Math.round(TranslationPreviewText.SMALL_SCREEN_HEIGHT * scale);
        int frameX = (width - frameW) / 2;
        int frameY = TOP + font.lineHeight + GAP;

        drawCentered(g, Component.translatable(frameShrunk()
            ? "gui.dungeontrain.translate.preview.frame.shrunk"
            : "gui.dungeontrain.translate.preview.frame"), TOP, LABEL_COLOUR);

        g.fill(frameX - 1, frameY - 1, frameX + frameW + 1, frameY + frameH + 1, 0xFF808080);
        g.fill(frameX, frameY, frameX + frameW, frameY + frameH, 0xFF1E2530);

        // Clipped in screen space, before the transform, so nothing drawn inside can spill out.
        g.enableScissor(frameX, frameY, frameX + frameW, frameY + frameH);
        g.pose().pushPose();
        g.pose().translate(frameX, frameY, 0);
        g.pose().scale(scale, scale, 1);
        if (kind == TranslationPreviewKind.CHAT) {
            renderChat(g);
        } else {
            for (Button sample : sampleButtons) {
                // Off-screen mouse: never hovered, so it shows the resting look players see.
                sample.render(g, -1, -1, 0);
            }
        }
        g.pose().popPose();
        g.disableScissor();

        int y = frameY + frameH + GAP;
        for (Component line : readout) {
            drawCentered(g, line, y, LABEL_COLOUR);
            y += font.lineHeight + 2;
        }
    }

    private void drawCentered(GuiGraphics g, Component line, int y, int colour) {
        g.drawCenteredString(font, line, width / 2, y, colour);
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
        super.render(g, mouseX, mouseY, partialTick);
        if (kind == TranslationPreviewKind.BOOK) {
            renderBookCaption(g);
        } else {
            g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
