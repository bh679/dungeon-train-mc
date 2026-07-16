package games.brennan.dungeontrain.client.localization;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Inline "Localized by X" text sitting immediately left of the title
 * screen's vanilla language button (see {@code TitleScreenLayoutHandler}),
 * shown only when {@link LocalizationCreditRegistry} has an entry for the
 * client's currently selected locale. Plain text, not a bordered button —
 * only the credited name(s) are individually clickable, and only when they
 * carry a {@code url}.
 *
 * <p>Wraps onto a second line above the first when the credited names don't
 * fit between the screen's left edge and the language button — both lines
 * stay right-aligned to the same edge, and the block is bottom-aligned so it
 * grows upward instead of spilling past the screen edge or the button.</p>
 */
public final class LocalizationCreditLabel extends AbstractWidget {

    private static final int PREFIX_COLOR = 0xFFAAAAAA;
    private static final int LINK_COLOR = 0xFF6699FF;
    private static final int LINK_HOVER_COLOR = 0xFFFFFFAA;
    private static final int SCREEN_MARGIN = 4;
    private static final int MAX_LINES = 5;

    private final Screen parent;
    private final List<Piece> pieces = new ArrayList<>();
    private final int lineHeight;

    private LocalizationCreditLabel(Screen parent, int x, int y, int width, int height, int lineHeight) {
        super(x, y, width, height, Component.empty());
        this.parent = parent;
        this.lineHeight = lineHeight;
    }

    /**
     * Build a label right-aligned {@code gap} px left of {@code anchorX}, with its
     * bottom edge aligned {@code gap} px above {@code anchorBottomY} — wrapping onto
     * a second line (growing upward) when it wouldn't otherwise fit before the
     * screen's left edge. Returns {@code null} when {@code credits} is empty —
     * callers should simply not add the widget.
     */
    public static LocalizationCreditLabel createLeftOf(Screen parent, List<LocalizationCredit> credits,
                                                         int anchorX, int anchorBottomY, int gap) {
        if (credits.isEmpty()) {
            return null;
        }
        Font font = Minecraft.getInstance().font;
        String prefixText = Component.translatable("gui.dungeontrain.localization_credits.prefix").getString();

        int rightEdge = anchorX - gap;
        int maxLineWidth = Math.max(font.width("…"), rightEdge - SCREEN_MARGIN);
        int space = font.width(" ");

        // Tokenize into words. Space-delimited scripts wrap between words; a single token wider than
        // a line (no-space scripts like Thai/Chinese, or one over-long word) is broken into character
        // chunks so nothing runs off the edge. `spaceBefore` records where a real space belongs, so
        // char-chunks join seamlessly while separate words stay spaced.
        List<Word> words = new ArrayList<>();
        boolean firstToken = true;
        for (String w : prefixText.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            addToken(words, w, null, !firstToken, maxLineWidth, font);
            firstToken = false;
        }
        for (int i = 0; i < credits.size(); i++) {
            LocalizationCredit credit = credits.get(i);
            String url = credit.url().orElse(null);
            String[] nameWords = credit.name().trim().split("\\s+");
            for (int j = 0; j < nameWords.length; j++) {
                String w = nameWords[j];
                if (w.isEmpty()) continue;
                // Comma-join consecutive credits (attach to the last token of a non-final credit).
                if (j == nameWords.length - 1 && i < credits.size() - 1) w = w + ",";
                addToken(words, w, url, !firstToken, maxLineWidth, font);
                firstToken = false;
            }
        }

        // Greedy-pack onto lines (up to MAX_LINES), adding a space only where one belongs.
        List<List<Word>> lines = new ArrayList<>();
        List<Word> current = new ArrayList<>();
        int currentWidth = 0;
        for (Word word : words) {
            int w = font.width(word.text());
            int gapBefore = (!current.isEmpty() && word.spaceBefore()) ? space : 0;
            if (!current.isEmpty() && currentWidth + gapBefore + w > maxLineWidth && lines.size() + 1 < MAX_LINES) {
                lines.add(current);
                current = new ArrayList<>();
                currentWidth = 0;
                gapBefore = 0;
            }
            current.add(word);
            currentWidth += gapBefore + w;
        }
        lines.add(current);

        int totalHeight = lines.size() * font.lineHeight;
        int topY = anchorBottomY - totalHeight;

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        LocalizationCreditLabel label = new LocalizationCreditLabel(parent, 0, topY, 0, totalHeight, font.lineHeight);
        for (int line = 0; line < lines.size(); line++) {
            List<Word> lineWords = lines.get(line);
            int lineWidth = 0;
            for (int k = 0; k < lineWords.size(); k++) {
                if (k > 0 && lineWords.get(k).spaceBefore()) lineWidth += space;
                lineWidth += font.width(lineWords.get(k).text());
            }
            int y = topY + line * font.lineHeight;
            int x = rightEdge - lineWidth;
            for (int k = 0; k < lineWords.size(); k++) {
                Word word = lineWords.get(k);
                if (k > 0 && word.spaceBefore()) x += space;
                int w = font.width(word.text());
                label.pieces.add(new Piece(word.text(), x, x + w, y, word.url()));
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x + w);
                x += w;
            }
        }
        label.setX(minX);
        label.setWidth(Math.max(0, maxX - minX));
        return label;
    }

    /** Add {@code text} as one word, or several char-chunks when it alone exceeds a line's width. */
    private static void addToken(List<Word> out, String text, String url, boolean spaceBefore,
                                 int maxLineWidth, Font font) {
        if (font.width(text) <= maxLineWidth) {
            out.add(new Word(text, url, spaceBefore));
            return;
        }
        StringBuilder chunk = new StringBuilder();
        boolean first = true;
        for (int c = 0; c < text.length(); c++) {
            if (chunk.length() > 0 && font.width(chunk.toString() + text.charAt(c)) > maxLineWidth) {
                out.add(new Word(chunk.toString(), url, first && spaceBefore));
                first = false;
                chunk.setLength(0);
            }
            chunk.append(text.charAt(c));
        }
        if (chunk.length() > 0) out.add(new Word(chunk.toString(), url, first && spaceBefore));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        for (Piece piece : pieces) {
            boolean hover = piece.url() != null
                    && mouseX >= piece.startX() && mouseX < piece.endX()
                    && mouseY >= piece.y() && mouseY < piece.y() + lineHeight;
            int color = piece.url() == null ? PREFIX_COLOR : (hover ? LINK_HOVER_COLOR : LINK_COLOR);
            graphics.drawString(font, piece.text(), piece.startX(), piece.y(), color, false);
            if (hover) {
                graphics.fill(piece.startX(), piece.y() + font.lineHeight,
                        piece.endX(), piece.y() + font.lineHeight + 1, color);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (Piece piece : pieces) {
            if (piece.url() != null
                    && mouseX >= piece.startX() && mouseX < piece.endX()
                    && mouseY >= piece.y() && mouseY < piece.y() + lineHeight) {
                openLink(parent, piece.url());
                return true;
            }
        }
        return false;
    }

    private static void openLink(Screen linkParent, String url) {
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) {
                Util.getPlatform().openUri(URI.create(url));
            }
            Minecraft.getInstance().setScreen(linkParent);
        }, url, true));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        StringBuilder sb = new StringBuilder();
        for (Piece piece : pieces) {
            sb.append(piece.text());
        }
        output.add(NarratedElementType.TITLE, Component.literal(sb.toString()));
    }

    /** One word/segment before line-wrapping. {@code spaceBefore} = a real space precedes it. */
    private record Word(String text, String url, boolean spaceBefore) {}

    /** One rendered segment at an absolute screen position after line-wrapping. */
    private record Piece(String text, int startX, int endX, int y, String url) {}
}
