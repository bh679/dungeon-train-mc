package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.support.BookCredits;
import games.brennan.dungeontrain.narrative.BookBoostTag;
import games.brennan.dungeontrain.narrative.BookPriorityTag;
import games.brennan.dungeontrain.narrative.PriorityBookMessage;
import games.brennan.dungeontrain.net.BookBoostPacket;
import games.brennan.dungeontrain.net.BookPriorityPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The star on the vote page, left of the thumbs: the one Priority Book control, with no line of text
 * on the page.
 *
 * <ul>
 *   <li><b>Somebody else's book</b>: each click is one boost, a tenth of one of the reader's Priority
 *       Books, and lifts the book like a whole one. Boosts repeat, so the star wears a count in its
 *       corner like a stack of items, going up with every click. One click each: a boost is small, and
 *       the count is the feedback.</li>
 *   <li><b>Your own released book</b>: the star makes it a Priority Book, spending a whole one (ten
 *       boosts), once. Two clicks, since that cannot be undone; the hover says "click again" between.</li>
 * </ul>
 *
 * <p>Beside the book sits the wallet ({@link #renderWallet}): the reader's boosts left, and the page's
 * only explanation, on its hover. Invisible to anyone with nothing to give: on somebody else's book it shows while
 * {@link BookCredits#boostsLeft} is above zero or this copy already carries the reader's boosts; on your
 * own while you have a Priority Book to spend or it already is one. {@link BookVoteClientEvents} owns the
 * page and its geometry and calls in here; the hit box is the star drawn on the last frame.</p>
 */
final class BookBoostControl {

    private static final int BUTTON_SIZE = 18;
    /** Tooltips wrap here: vanilla's never wrap, and the explanation is a sentence. */
    private static final int TOOLTIP_WIDTH = 200;
    private static final int COLOR_LABEL = 0x0C0602;
    private static final int COLOR_COUNT = 0xFFFFFF;
    private static final int COLOR_COST = 0xFF6B5A;      // the "-N" beside the wallet: boosts used on this book
    private static final float COST_SCALE = 0.75F;
    /** What making your own book a Priority Book costs, in boosts: a whole Priority Book. */
    private static final int OWN_BOOK_COST = 10;

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
    /** The author's own copy is a Priority Book: seeded from its tag, set at once on a spend. */
    private static boolean priority = false;
    /** Own book only: the first click armed the spend; a second commits. */
    private static boolean armed = false;
    // Last frame's hit box; width 0 = not drawn.
    private static int starX, starY, starW;

    private BookBoostControl() {}

    /** A book was opened (or turned out to be the reader's own): read what it already carries. */
    static void open(ItemStack stack) {
        given = BookBoostTag.count(stack);
        priority = BookPriorityTag.isPriority(stack);
        armed = false;
        starW = 0;
    }

    static void reset() {
        given = 0;
        priority = false;
        armed = false;
        starW = 0;
    }

    /** Forget a half-made spend: a click elsewhere, a page turn. */
    static void disarm() {
        armed = false;
    }

    /** Begin a frame: nothing is clickable until it is drawn again. */
    static void beginFrame() {
        starW = 0;
    }

    /**
     * Whether the star exists on this book. {@code ownReleased} is the reader's own book, out on the
     * line; {@code own} the reader's own in any state (never boostable, and only released ones can be
     * made Priority Books).
     */
    static boolean shown(boolean own, boolean ownReleased) {
        if (own) return ownReleased && (priority || BookCredits.current() > 0);
        return BookCredits.boostsLeft() > 0 || given > 0;
    }

    /** The star at ({@code x}, {@code y}), its label under it at {@code labelY}, and its hover. */
    static void render(GuiGraphics gfx, Font font, boolean own, int x, int y, int labelY, int mouseX, int mouseY) {
        starX = x;
        starY = y;
        starW = BUTTON_SIZE;
        boolean hover = inStar(mouseX, mouseY);
        boolean clickable = own ? !priority && BookCredits.current() > 0 : BookCredits.boostsLeft() > 0;
        boolean lit = clickable && (hover || armed);
        boolean filled = own ? priority : given > 0;
        ResourceLocation sprite = filled
            ? (lit ? STAR_FILLED_HIGHLIGHTED : STAR_FILLED)
            : (lit ? STAR_HIGHLIGHTED : STAR);
        gfx.blitSprite(sprite, x, y, BUTTON_SIZE, BUTTON_SIZE);
        if (!own && given > 0) drawCount(gfx, font, given);

        Component label = PriorityBookMessage.boostLabel();
        gfx.drawString(font, label, x + BUTTON_SIZE / 2 - font.width(label) / 2, labelY, COLOR_LABEL, false);

        // No explanation here (that lives on the wallet star); only the "click again" a whole
        // Priority Book's spend on your own book asks for.
        if (hover && own && armed) tooltip(gfx, font, List.of(PriorityBookMessage.makeConfirm()), mouseX, mouseY);
    }

    /**
     * The wallet: a filled star outside the book's bottom-right corner with the boosts the reader has
     * left beside it, going down as they spend them. Its hover is the one explanation on the page.
     * Drawn whenever the page's star is, so the count is always next to what spends it.
     */
    static void renderWallet(GuiGraphics gfx, Font font, boolean own, int x, int y, int mouseX, int mouseY) {
        int left = BookCredits.boostsLeft();
        gfx.blitSprite(STAR_FILLED, x, y, BUTTON_SIZE, BUTTON_SIZE);
        String count = Integer.toString(left);
        int countX = x + BUTTON_SIZE + 2;
        gfx.drawString(font, count, countX, y + (BUTTON_SIZE - 8) / 2, COLOR_COUNT, true);
        int w = BUTTON_SIZE + 2 + font.width(count);
        // Small, beside the count: the boosts this reader has put into THIS book. Each boost on
        // somebody else's is one; making your own a Priority Book is ten.
        int used = own ? (priority ? OWN_BOOK_COST : 0) : given;
        if (used > 0) drawCost(gfx, font, used, countX + font.width(count) + 2, y);
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BUTTON_SIZE;
        if (hover) tooltip(gfx, font, List.of(PriorityBookMessage.boostTooltip()), mouseX, mouseY);
    }

    /** The small "-N", top-aligned with the wallet count like a superscript. */
    private static void drawCost(GuiGraphics gfx, Font font, int used, int x, int y) {
        gfx.pose().pushPose();
        gfx.pose().translate(x, y + (BUTTON_SIZE - 8) / 2F - 1, 0);
        gfx.pose().scale(COST_SCALE, COST_SCALE, 1F);
        gfx.drawString(font, "-" + used, 0, 0, COLOR_COST, true);
        gfx.pose().popPose();
    }

    /** The count in the star's bottom-right corner, drawn the way an item stack draws its size. */
    private static void drawCount(GuiGraphics gfx, Font font, int n) {
        String text = Integer.toString(n);
        gfx.pose().pushPose();
        gfx.pose().translate(0, 0, 200);
        gfx.drawString(font, text, starX + BUTTON_SIZE + 1 - font.width(text), starY + BUTTON_SIZE - 7,
            COLOR_COUNT, true);
        gfx.pose().popPose();
    }

    /**
     * A left click on the page. True when it was the star and the click is consumed. On somebody else's
     * book that is one boost; on your own, the first click arms and the second spends. Any other click
     * disarms and returns false.
     */
    static boolean click(int mx, int my, boolean own, String bookType, String bookId) {
        if (!inStar(mx, my)) {
            armed = false;
            return false;
        }
        if (own) return clickOwn(bookType, bookId);
        if (BookCredits.boostsLeft() <= 0) return true;
        given++;
        DungeonTrainNet.sendToServer(new BookBoostPacket(bookType, bookId));
        BookCredits.boostedOne();
        return true;
    }

    /**
     * Make your own book a Priority Book. Shown at once (optimistic): the server stamps the copy when the
     * relay confirms, and if it refuses, the chat line says no book was used and the next open reads the
     * stack's real state.
     */
    private static boolean clickOwn(String bookType, String bookId) {
        if (priority || BookCredits.current() <= 0) return true;
        if (!armed) {
            armed = true;
            return true;
        }
        armed = false;
        priority = true;
        DungeonTrainNet.sendToServer(new BookPriorityPacket(bookType, bookId));
        BookCredits.spentOne();
        return true;
    }

    private static void tooltip(GuiGraphics gfx, Font font, List<Component> parts, int mouseX, int mouseY) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component part : parts) lines.addAll(font.split(part, TOOLTIP_WIDTH));
        gfx.renderTooltip(font, lines, mouseX, mouseY);
    }

    private static boolean inStar(int x, int y) {
        return starW > 0 && x >= starX && x < starX + starW && y >= starY && y < starY + BUTTON_SIZE;
    }

    private static String locale() {
        try {
            return Minecraft.getInstance().getLanguageManager().getSelected();
        } catch (Throwable t) {
            return "";
        }
    }
}
