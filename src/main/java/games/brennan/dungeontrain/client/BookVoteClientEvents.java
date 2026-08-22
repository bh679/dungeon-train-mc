package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.BookViewScreenAccessor;
import games.brennan.dungeontrain.narrative.BookReportTag;
import games.brennan.dungeontrain.narrative.BookVoteTag;
import games.brennan.dungeontrain.net.BookReportPacket;
import games.brennan.dungeontrain.net.BookVotePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The 👍/👎 vote page — a VIRTUAL page appended after the last real page of every PLAYER-WRITTEN
 * community book the player reads ({@code shared} identity only — dev-authored random/starting/
 * narrative content, deathnotes and letters are untouched). Zero mixins and zero stack mutation: on
 * {@code Init.Post} the screen's public {@link BookViewScreen#setBookAccess} is handed a copy of the
 * real pages plus one EMPTY page, so the vanilla forward PageButton, page indicator and page-turn
 * flow all "discover" the extra page on their own — and everything on it (warm dim, the train's
 * prompt, both thumbs, labels) is drawn by this class in {@code Render.Post}, so the page reads
 * visually as the TRAIN's page, not the author's parchment.
 *
 * <p>The page shows {@code "The train asks,"} plus one of 10 questions (picked deterministically
 * per book, so a book always asks the same thing) over 👍/👎 thumbs labelled {@code (Y)es}/
 * {@code (N)o}. Voting — clicking a thumb, or pressing <b>Y</b>/<b>N</b> from ANY page — commits
 * instantly: the {@link BookVotePacket} is sent (the server re-validates the held stack, stamps
 * {@link BookVoteTag} and consent-gates the relay report), the book CLOSES, and the train answers
 * with one random chat line drawn from the matching 10 responses plus 10 general ones. Closing or
 * throwing without voting registers nothing. Lectern reads are excluded, same guard as read
 * telemetry ({@link BookReadClientEvents}) — which also range-checks the vote page out of its
 * dwell math.</p>
 *
 * <p>Below the thumbs sits the ⚠ <b>report</b> control — the escalation above 👎: not "I disliked
 * this" but "this should not be in the pool". It is drawn as text rather than a sprite (it
 * localizes, and the page is cramped), and it is deliberately <b>two-tap</b>: the first click arms
 * it, a second commits. A mistyped vote is harmless; a mistaken report is not, which is also why
 * there is no keyboard shortcut for it. Committing sends {@link BookReportPacket} and closes the
 * book exactly as a vote does, but casts no vote — the two are independent verdicts. A book this
 * player already reported ({@link BookReportTag}) shows an inert "Reported" line instead: a report
 * cannot be taken back or repeated.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BookVoteClientEvents {

    // Book background geometry (BookViewScreen): 192px art anchored at ((width-192)/2, 2); the page
    // column is 114px wide starting 36px in, so the page's center X is left + 93.
    private static final int BOOK_TOP = 2;
    private static final int PAGE_CENTER_X_OFFSET = 93;
    private static final int TEXT_X_OFFSET = 36;
    private static final int TEXT_WIDTH = 114;
    private static final int PREFIX_Y = BOOK_TOP + 38;
    private static final int PROMPT_Y = PREFIX_Y + 12;
    private static final int BUTTON_SIZE = 18;
    private static final int BUTTONS_Y = BOOK_TOP + 90;
    private static final int BUTTON_GAP = 20;            // between the two thumbs
    private static final int LABELS_Y = BUTTONS_Y + BUTTON_SIZE + 6;
    private static final int REPORT_Y = LABELS_Y + 20;   // clear of the labels, above the page number
    private static final int REPORT_PAD_X = 3;           // hit-box slack either side of the text
    private static final int REPORT_PAD_Y = 2;

    // Warm leather dim over the whole page (approved variant A) + the train's rust-orange voice.
    private static final int DIM_COLOR = 0x5A48220A;     // ARGB (72,34,10) @ alpha 90
    // Exact paper bounds of book.png (sampled: x 26-157, y 8-172) so the dim covers the whole page.
    private static final int DIM_X1 = 26, DIM_Y1 = 8, DIM_X2 = 158, DIM_Y2 = 173; // book-local
    private static final int COLOR_PREFIX = 0x5C2C0E;    // rust-orange "The train asks,"
    private static final int COLOR_TEXT = 0x0C0602;      // ink black
    private static final int COLOR_REPORT = 0x6B1B10;    // muted red — present, not shouting
    private static final int COLOR_REPORT_ARMED = 0x9E1B0C;
    private static final int COLOR_REPORTED = 0x4A423C;  // spent/grey once the report is in
    private static final int PROMPT_COUNT = 10;
    private static final int RESPONSE_COUNT = 10;        // per set (yes / no / general)
    private static final int REPORT_RESPONSE_COUNT = 5;  // train lines for a report

    private static final ResourceLocation UP_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/thumbs_up");
    private static final ResourceLocation UP_HIGHLIGHTED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/thumbs_up_highlighted");
    private static final ResourceLocation DOWN_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/thumbs_down");
    private static final ResourceLocation DOWN_HIGHLIGHTED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/thumbs_down_highlighted");

    // --- single tracked votable book screen (one book screen is open at a time) ---
    private static boolean active = false;
    private static BookViewScreen screen = null;
    private static String bookType = null;
    private static String bookId = null;
    private static int variantIndex = -1;
    private static List<Component> realPages = null;     // the book's REAL pages (vote page excluded)
    private static int selectedVote = 0;                 // 0 none, ±1 — seeded from the stack's tag
    private static int promptIndex = 1;                  // 1-based, deterministic per book
    private static boolean reported = false;             // seeded from the stack's tag — one-way
    private static boolean reportArmed = false;          // first click armed it; a second commits

    private BookVoteClientEvents() {}

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        reset();
        Screen opening = event.getScreen();
        // Held-book reads only — a LecternScreen's book isn't in hand (same guard as telemetry).
        if (!(opening instanceof BookViewScreen book) || opening instanceof LecternScreen) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack stack = votable(player.getMainHandItem());
        if (stack == null) stack = votable(player.getOffhandItem());
        if (stack == null) return;

        BookViewScreen.BookAccess access = BookViewScreen.BookAccess.fromItem(stack);
        if (access == null || access.getPageCount() <= 0) { reset(); return; }
        realPages = List.copyOf(access.pages());
        OptionalInt vote = BookVoteTag.read(stack);
        selectedVote = vote.isPresent() ? vote.getAsInt() : 0;
        reported = BookReportTag.isReported(stack);
        // The train always asks a book the same question: stable per (bookType, bookId).
        promptIndex = Math.floorMod((bookType + ":" + bookId).hashCode(), PROMPT_COUNT) + 1;
        screen = book;
        active = true;
    }

    /**
     * Append the (empty) vote page. {@code Init.Post} re-fires on every resize, so this is rebuilt
     * from {@link #realPages} each time. The page's visuals are entirely {@link #onScreenRenderPost}'s.
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!active || event.getScreen() != screen) return;
        List<Component> pages = new ArrayList<>(realPages);
        pages.add(Component.empty());
        screen.setBookAccess(new BookViewScreen.BookAccess(pages));
    }

    /** Draw the train's page: warm dim → prompt → thumbs (hover/selected lit) → labels. */
    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!active || event.getScreen() != screen) return;
        if (!onVotePage()) {
            reportArmed = false; // turning off the page abandons a half-made report
            return;
        }
        GuiGraphics gfx = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int left = bookLeft();
        int mouseX = event.getMouseX();
        int mouseY = event.getMouseY();

        // Warm leather dim over the page — visibly NOT the author's parchment.
        gfx.fill(left + DIM_X1, BOOK_TOP + DIM_Y1, left + DIM_X2, BOOK_TOP + DIM_Y2, DIM_COLOR);

        // "The train asks," + this book's question, both centered in the page column.
        int centerX = left + PAGE_CENTER_X_OFFSET;
        Component prefix = Component.translatable("gui.dungeontrain.book_vote.ask_prefix");
        gfx.drawString(font, prefix, centerX - font.width(prefix) / 2, PREFIX_Y, COLOR_PREFIX, false);
        int y = PROMPT_Y;
        Component prompt = Component.translatable("gui.dungeontrain.book_vote.prompt." + promptIndex);
        for (FormattedCharSequence line : font.split(prompt, TEXT_WIDTH)) {
            gfx.drawString(font, line, centerX - font.width(line) / 2, y, COLOR_TEXT, false);
            y += 9;
        }

        // Thumbs — highlighted when hovered or when that side is the current vote.
        boolean upLit = selectedVote == 1 || inUpButton(mouseX, mouseY);
        boolean downLit = selectedVote == -1 || inDownButton(mouseX, mouseY);
        gfx.blitSprite(upLit ? UP_HIGHLIGHTED_SPRITE : UP_SPRITE, upX(), BUTTONS_Y, BUTTON_SIZE, BUTTON_SIZE);
        gfx.blitSprite(downLit ? DOWN_HIGHLIGHTED_SPRITE : DOWN_SPRITE, downX(), BUTTONS_Y, BUTTON_SIZE, BUTTON_SIZE);

        Component yes = Component.translatable("gui.dungeontrain.book_vote.approve");
        Component no = Component.translatable("gui.dungeontrain.book_vote.reject");
        gfx.drawString(font, yes, upX() + BUTTON_SIZE / 2 - font.width(yes) / 2, LABELS_Y, COLOR_TEXT, false);
        gfx.drawString(font, no, downX() + BUTTON_SIZE / 2 - font.width(no) / 2, LABELS_Y, COLOR_TEXT, false);

        // The report control — quiet by default, louder once armed, spent once used. Underlined
        // while hovered so it reads as clickable without a button chrome the page has no room for.
        Component report = reportLabel();
        int color = reported ? COLOR_REPORTED : reportArmed ? COLOR_REPORT_ARMED : COLOR_REPORT;
        boolean hot = !reported && inReport(mouseX, mouseY);
        gfx.drawString(font, hot ? report.copy().withStyle(ChatFormatting.UNDERLINE) : report,
            centerX - font.width(report) / 2, REPORT_Y, color, false);
    }

    /** What the report control currently says: offer → confirm → spent. */
    private static Component reportLabel() {
        if (reported) return Component.translatable("gui.dungeontrain.book_vote.reported");
        return Component.translatable(reportArmed
            ? "gui.dungeontrain.book_vote.report_confirm"
            : "gui.dungeontrain.book_vote.report");
    }

    /** Thumb clicks on the vote page — instant commit (the screen closes, so consume the click). */
    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!active || event.getScreen() != screen || !onVotePage()) return;
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        int mx = (int) event.getMouseX();
        int my = (int) event.getMouseY();
        if (inUpButton(mx, my)) {
            event.setCanceled(true);
            clickSound();
            applyVote(1);
        } else if (inDownButton(mx, my)) {
            event.setCanceled(true);
            clickSound();
            applyVote(-1);
        } else if (!reported && inReport(mx, my)) {
            // Two-tap: arm, then commit. A stray click anywhere else on the page disarms, so a
            // report always takes two deliberate clicks in the same spot.
            event.setCanceled(true);
            clickSound();
            if (reportArmed) applyReport(); else reportArmed = true;
        } else {
            reportArmed = false;
        }
    }

    /** Y/N from ANY page — instant commit, no need to visit the vote page first. */
    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Post event) {
        if (!active || event.getScreen() != screen) return;
        int key = event.getKeyCode();
        if (key != GLFW.GLFW_KEY_Y && key != GLFW.GLFW_KEY_N) return;
        applyVote(key == GLFW.GLFW_KEY_Y ? 1 : -1);
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() == screen) reset();
    }

    /**
     * Commit the vote: send the packet (server validates + stamps + consent-gates the relay POST),
     * CLOSE the book (fires the normal Closing flow — read telemetry included), then have the train
     * answer with one random chat line from the matching set plus the general set (20 candidates).
     */
    private static void applyVote(int vote) {
        if (!active || (vote != 1 && vote != -1)) return;
        selectedVote = vote;
        DungeonTrainNet.sendToServer(new BookVotePacket(bookType, bookId, vote, variantIndex));

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        BookViewScreen closing = screen;
        if (mc.screen == closing) mc.setScreen(null); // triggers Closing → reset()

        if (player != null) {
            int pick = player.getRandom().nextInt(RESPONSE_COUNT * 2); // matching 10 + general 10
            String key = pick < RESPONSE_COUNT
                ? "gui.dungeontrain.book_vote.response." + (vote == 1 ? "yes." : "no.") + (pick + 1)
                : "gui.dungeontrain.book_vote.response.general." + (pick - RESPONSE_COUNT + 1);
            // Same styling as every other DT flavor chat line (e.g. AdvancementsHintClient).
            player.displayClientMessage(
                Component.translatable(key).withStyle(ChatFormatting.GRAY), false);
        }
    }

    /**
     * Commit the report: send the packet (server validates + stamps + consent-gates the relay POST),
     * CLOSE the book (the normal Closing flow — read telemetry included), then have the train
     * acknowledge it. No vote is cast: "pull this" and "I disliked this" are different verdicts, and
     * folding one into the other would put a 👎 on the record the player never gave.
     */
    private static void applyReport() {
        if (!active || reported) return;
        reported = true;
        reportArmed = false;
        DungeonTrainNet.sendToServer(new BookReportPacket(bookType, bookId));

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        BookViewScreen closing = screen;
        if (mc.screen == closing) mc.setScreen(null); // triggers Closing → reset()

        if (player != null) {
            int pick = player.getRandom().nextInt(REPORT_RESPONSE_COUNT) + 1;
            player.displayClientMessage(
                Component.translatable("gui.dungeontrain.book_vote.report_response." + pick)
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    private static void clickSound() {
        Minecraft.getInstance().getSoundManager()
            .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private static int bookLeft() {
        return (screen.width - 192) / 2;
    }

    private static int upX() {
        return bookLeft() + PAGE_CENTER_X_OFFSET - BUTTON_SIZE - BUTTON_GAP / 2;
    }

    private static int downX() {
        return bookLeft() + PAGE_CENTER_X_OFFSET + BUTTON_GAP / 2;
    }

    private static boolean inUpButton(int x, int y) {
        return x >= upX() && x < upX() + BUTTON_SIZE && y >= BUTTONS_Y && y < BUTTONS_Y + BUTTON_SIZE;
    }

    private static boolean inDownButton(int x, int y) {
        return x >= downX() && x < downX() + BUTTON_SIZE && y >= BUTTONS_Y && y < BUTTONS_Y + BUTTON_SIZE;
    }

    /**
     * The report control's hit box — derived from the current label's width, since the three states
     * ("report" / "click again" / "reported") are different lengths and localize to different
     * lengths again. Centered on the page column, same as the drawn text.
     */
    private static boolean inReport(int x, int y) {
        Font font = Minecraft.getInstance().font;
        int w = font.width(reportLabel());
        int left = bookLeft() + PAGE_CENTER_X_OFFSET - w / 2;
        return x >= left - REPORT_PAD_X && x < left + w + REPORT_PAD_X
            && y >= REPORT_Y - REPORT_PAD_Y && y < REPORT_Y + 9 + REPORT_PAD_Y;
    }

    private static boolean onVotePage() {
        return ((BookViewScreenAccessor) (Object) screen).dungeontrain$getCurrentPage() >= realPages.size();
    }

    /**
     * {@code stack} when it is a votable book (sets the identity fields), else null. Only
     * PLAYER-WRITTEN community books ({@code shared} — discovered submissions from other players)
     * are votable; dev-authored content (random/starting/narrative) and everything untagged is not.
     */
    private static ItemStack votable(ItemStack stack) {
        Optional<BookIdentity> id = BookIdentity.resolve(stack);
        if (id.isEmpty() || !"shared".equals(id.get().bookType())) return null;
        bookType = id.get().bookType();
        bookId = id.get().bookId();
        variantIndex = id.get().variantIndex();
        return stack;
    }

    private static void reset() {
        active = false;
        screen = null;
        bookType = null;
        bookId = null;
        variantIndex = -1;
        realPages = null;
        selectedVote = 0;
        promptIndex = 1;
        reported = false;
        reportArmed = false;
    }
}
