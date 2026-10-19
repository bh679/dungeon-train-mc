package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.support.BookCredits;
import games.brennan.dungeontrain.narrative.BookBoostTag;
import games.brennan.dungeontrain.narrative.PriorityBookMessage;
import games.brennan.dungeontrain.net.BookBoostPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The Boost control on the vote page of SOMEBODY ELSE's community book: a star beside the thumbs and a
 * gold "Boost this book (N boosts left)" line above them. Either one spends a boost, a tenth of one of
 * the reader's Priority Books, and lifts the book like a whole one in other players' trains.
 *
 * <p>Invisible to anyone with nothing to give: both parts draw only while {@link BookCredits#boostsLeft}
 * is above zero, or once this copy has been boosted (then the star is filled and the line says so).
 * Two taps, like Report, because a boost cannot be taken back; a reader may boost the same book again.
 * Unlike a vote, it does not close the book, so the count going down is the feedback.</p>
 *
 * <p>{@link BookVoteClientEvents} owns the page and its geometry and calls in here. The hit boxes are the
 * rectangles drawn on the last frame, so a click always lands on what the player can see.</p>
 */
final class BookBoostControl {

    private static final int BUTTON_SIZE = 18;
    private static final int LINE_H = 9;
    private static final int COLOR_GOLD = 0x8A5A00;      // same gold as the author's Priority Book line
    private static final int COLOR_ARMED = 0x9E1B0C;     // the "click again" red the page already uses
    private static final int COLOR_TEXT = 0x0C0602;

    private static final ResourceLocation STAR =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/star");
    private static final ResourceLocation STAR_HIGHLIGHTED =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/star_highlighted");
    private static final ResourceLocation STAR_FILLED =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/star_filled");
    private static final ResourceLocation STAR_FILLED_HIGHLIGHTED =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/star_filled_highlighted");

    /** How many times this reader has boosted the open copy, seeded from its tag. */
    private static int given = 0;
    private static boolean armed = false;
    // Last frame's hit boxes; width 0 = not drawn.
    private static int starX, starY, starW;
    private static int lineX, lineY, lineW, lineH;

    private BookBoostControl() {}

    /** A book was opened: read what the reader has already given it. */
    static void open(ItemStack stack) {
        given = BookBoostTag.count(stack);
        armed = false;
        clearHitBoxes();
    }

    static void reset() {
        given = 0;
        armed = false;
        clearHitBoxes();
    }

    /** Forget a half-made boost: a click elsewhere, a page turn, another control armed. */
    static void disarm() {
        armed = false;
    }

    /** Whether the control exists at all on this book. Never on the reader's own. */
    static boolean shown(boolean ownBook) {
        return !ownBook && (BookCredits.boostsLeft() > 0 || given > 0);
    }

    private static boolean canBoost() {
        return BookCredits.boostsLeft() > 0;
    }

    /** Begin a frame: nothing is clickable until it is drawn again. */
    static void beginFrame() {
        clearHitBoxes();
    }

    /**
     * The star on the thumbs row at ({@code x}, {@code y}), with its label centred under it. Call after
     * {@link #renderLine}: the star's tooltip stands in for the line when the line had no room.
     */
    static void renderStar(GuiGraphics gfx, Font font, int x, int y, int labelY, int mouseX, int mouseY) {
        starX = x;
        starY = y;
        starW = BUTTON_SIZE;
        boolean hover = inStar(mouseX, mouseY);
        boolean lit = canBoost() && (armed || hover);
        ResourceLocation sprite = given > 0
            ? (lit ? STAR_FILLED_HIGHLIGHTED : STAR_FILLED)
            : (lit ? STAR_HIGHLIGHTED : STAR);
        gfx.blitSprite(sprite, x, y, BUTTON_SIZE, BUTTON_SIZE);
        Component label = PriorityBookMessage.boostLabel();
        gfx.drawString(font, label, x + BUTTON_SIZE / 2 - font.width(label) / 2, labelY, COLOR_TEXT, false);
        Component text = lineText();
        if (hover && !lineDrawn() && text != null) {
            // No room for the line under a long question (it is drawn first, so this frame knows):
            // the tooltip carries what it would have said, the "click again" included.
            gfx.renderTooltip(font, armed ? text
                : text.copy().append(" ").append(PriorityBookMessage.boostTooltip()), mouseX, mouseY);
        } else if (hover && !armed) {
            gfx.renderTooltip(font, PriorityBookMessage.boostTooltip(), mouseX, mouseY);
        }
    }

    /**
     * The gold line, centred on {@code centerX} from {@code y}, wrapped to {@code width}. Drawn only when
     * every row fits in {@code maxRows}: a line cut off mid-phrase ("Boost this book (20") reads as
     * broken, so under a long question the star works alone and its tooltip says the line instead.
     */
    static void renderLine(GuiGraphics gfx, Font font, int centerX, int left, int width, int y, int maxRows,
                           int mouseX, int mouseY) {
        Component text = lineText();
        if (text == null) return;
        List<FormattedCharSequence> lines = font.split(text, width);
        if (lines.isEmpty() || lines.size() > maxRows) return;
        int rows = lines.size();
        lineX = left;
        lineY = y;
        lineW = width;
        lineH = rows * LINE_H;
        boolean hover = inLine(mouseX, mouseY);
        boolean underline = canBoost() && hover && !armed;
        if (underline) lines = font.split(text.copy().withStyle(ChatFormatting.UNDERLINE), width);
        int color = armed ? COLOR_ARMED : canBoost() ? COLOR_GOLD : COLOR_TEXT;
        int ly = y;
        for (int i = 0; i < rows; i++) {
            FormattedCharSequence line = lines.get(i);
            gfx.drawString(font, line, centerX - font.width(line) / 2, ly, color, false);
            ly += LINE_H;
        }
        if (hover && !armed) gfx.renderTooltip(font, PriorityBookMessage.boostTooltip(), mouseX, mouseY);
    }

    /**
     * A left click on the page. True when it was the star or the line and the click is consumed: the
     * first tap arms, the second sends. Any other click disarms and returns false.
     */
    static boolean click(int mx, int my, String bookType, String bookId) {
        if (!canBoost() || !(inStar(mx, my) || inLine(mx, my))) {
            armed = false;
            return false;
        }
        if (!armed) {
            armed = true;
            return true;
        }
        armed = false;
        given++;
        DungeonTrainNet.sendToServer(new BookBoostPacket(bookType, bookId));
        BookCredits.boostedOne();
        return true;
    }

    /** What the line says now, or null when there is nothing to say. */
    private static Component lineText() {
        if (armed) return PriorityBookMessage.boostConfirm();
        int left = BookCredits.boostsLeft();
        if (left > 0) return PriorityBookMessage.boost(locale(), left);
        return given > 0 ? PriorityBookMessage.youBoosted(locale(), given) : null;
    }

    private static boolean lineDrawn() {
        return lineW > 0;
    }

    private static boolean inStar(int x, int y) {
        return starW > 0 && x >= starX && x < starX + starW && y >= starY && y < starY + BUTTON_SIZE;
    }

    private static boolean inLine(int x, int y) {
        return lineW > 0 && x >= lineX && x < lineX + lineW && y >= lineY && y < lineY + lineH;
    }

    private static void clearHitBoxes() {
        starW = 0;
        lineW = 0;
    }

    private static String locale() {
        try {
            return Minecraft.getInstance().getLanguageManager().getSelected();
        } catch (Throwable t) {
            return "";
        }
    }
}
