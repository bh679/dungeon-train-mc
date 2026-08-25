package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.BookViewScreenAccessor;
import games.brennan.dungeontrain.narrative.BookModerationState;
import games.brennan.dungeontrain.narrative.BookModerationTag;
import games.brennan.dungeontrain.narrative.BookPrivateTag;
import games.brennan.dungeontrain.narrative.BookProtestTag;
import games.brennan.dungeontrain.narrative.BookVoteCountsTag;
import games.brennan.dungeontrain.narrative.BookReportTag;
import games.brennan.dungeontrain.narrative.BookVoteTag;
import games.brennan.dungeontrain.narrative.UnapprovedBookMessage;
import games.brennan.dungeontrain.net.BookPrivatePacket;
import games.brennan.dungeontrain.net.BookProtestPacket;
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
import net.minecraft.client.gui.screens.inventory.PageButton;
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
 * <p>Below the thumbs sits a third 18×18 icon — the ⚠ <b>report</b> control, the escalation above
 * 👎: not "I disliked this" but "this should not be in the pool". It is an icon for the same reason
 * the verdicts above it are: three controls on one row of the same footing, with no words until
 * words are needed. It is deliberately <b>two-tap</b> — the first click arms it and reveals the
 * confirmation line beneath, a second commits. A mistyped vote is harmless; a mistaken report is
 * not, which is also why there is no keyboard shortcut for it. Committing sends
 * {@link BookReportPacket} and closes the book exactly as a vote does, but casts no vote — the two
 * are independent verdicts. A book this player already reported ({@link BookReportTag}) shows the
 * icon dimmed over an inert "Reported" line: a report cannot be taken back or repeated.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BookVoteClientEvents {

    // Book background geometry (BookViewScreen): 192px art anchored at ((width-192)/2, 2); the page
    // column is 114px wide starting 36px in, so the page's center X is left + 93.
    //
    // Every Y here is a DELTA from the top of the book, never a screen coordinate. The book is not
    // reliably at BOOK_TOP — see bookTop(), which is where these are resolved against wherever it is
    // really being drawn.
    private static final int BOOK_TOP = 2;
    private static final int PAGE_CENTER_X_OFFSET = 93;
    private static final int TEXT_X_OFFSET = 36;
    private static final int TEXT_WIDTH = 114;
    private static final int PREFIX_DY = 38;
    private static final int PROMPT_DY = PREFIX_DY + 12;
    private static final int BUTTON_SIZE = 18;
    private static final int BUTTONS_DY = 90;
    private static final int BUTTON_GAP = 20;            // between the two thumbs
    private static final int LABELS_DY = BUTTONS_DY + BUTTON_SIZE + 6;
    private static final int REPORT_DY = LABELS_DY + 12; // its own row under the (Y)es/(N)o labels
    private static final int REPORT_TEXT_DY = REPORT_DY + BUTTON_SIZE + 3; // confirmation line, when shown
    // Vanilla nails both page-turn buttons to this y in createPageControlButtons(), which is what
    // makes the back button usable as a probe for how far the book has been moved. See bookTop().
    private static final int VANILLA_PAGE_BUTTON_Y = 159;

    // Warm leather dim over the whole page (approved variant A) + the train's rust-orange voice.
    private static final int DIM_COLOR = 0x5A48220A;     // ARGB (72,34,10) @ alpha 90
    // Exact paper bounds of book.png (sampled: x 26-157, y 8-172) so the dim covers the whole page.
    private static final int DIM_X1 = 26, DIM_Y1 = 8, DIM_X2 = 158, DIM_Y2 = 173; // book-local
    private static final int COLOR_PREFIX = 0x5C2C0E;    // rust-orange "The train asks,"
    private static final int COLOR_TEXT = 0x0C0602;      // ink black
    private static final int COLOR_REPORT_ARMED = 0x9E1B0C; // the "click again" line
    // Where the train stands on one of YOUR OWN books it has not released (see BookModerationState).
    // Orange while there is still an answer coming, red once there isn't. Both are read off the same
    // dimmed leather as everything else on this page, so they sit in its palette rather than shouting.
    private static final int COLOR_STATUS_WAITING = 0xB5500A;  // pending / undecided
    private static final int COLOR_STATUS_REJECTED = 0x9E1B0C; // rejected
    private static final int COLOR_REPORTED = 0x4A423C;     // spent/grey once the report is in
    private static final float REPORTED_ALPHA = 0.4F;       // the icon, dimmed, after reporting
    private static final int PROMPT_COUNT = 10;
    private static final int RESPONSE_COUNT = 10;        // per set (yes / no / general)
    private static final int REPORT_RESPONSE_COUNT = 5;  // train lines for a report
    private static final int PROTEST_RESPONSE_COUNT = 5; // ...for an author's protest
    private static final int PRIVATE_RESPONSE_COUNT = 5; // ...for withdrawing / restoring your own

    private static final ResourceLocation UP_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/thumbs_up");
    private static final ResourceLocation UP_HIGHLIGHTED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/thumbs_up_highlighted");
    private static final ResourceLocation DOWN_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/thumbs_down");
    private static final ResourceLocation DOWN_HIGHLIGHTED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/thumbs_down_highlighted");
    private static final ResourceLocation REPORT_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/report");
    private static final ResourceLocation REPORT_HIGHLIGHTED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/report_highlighted");
    // Withdraw / restore: a padlock that shows the state the book is in, and swaps to the state it
    // WILL be in while the pointer is over it — so the control answers "what does this do" before it
    // is pressed, rather than only after.
    private static final ResourceLocation PRIVATE_LOCKED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/private_locked");
    private static final ResourceLocation PRIVATE_LOCKED_HIGHLIGHTED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/private_locked_highlighted");
    private static final ResourceLocation PRIVATE_UNLOCKED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/private_unlocked");
    private static final ResourceLocation PRIVATE_UNLOCKED_HIGHLIGHTED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/private_unlocked_highlighted");

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
    // Where this book stands, when it is one of the reader's OWN that the train has not released.
    // APPROVED for every ordinary community book, which is the overwhelming majority.
    private static BookModerationState moderation = BookModerationState.PUBLIC;
    private static boolean isPrivate = false;            // seeded from the stack's tag — reversible
    private static boolean protested = false;            // seeded from the stack's tag — one-way
    // How this book is polling, when it is one of the reader's own. -1 = the relay never told us, and
    // is NOT the same as 0: zero votes is a real answer worth showing, an absent tally is not.
    private static int votesUp = -1;
    private static int votesDown = -1;
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
        moderation = BookModerationTag.read(stack);
        isPrivate = BookPrivateTag.isPrivate(stack);
        protested = BookProtestTag.isProtested(stack);
        boolean hasVotes = BookVoteCountsTag.has(stack);
        votesUp = hasVotes ? BookVoteCountsTag.up(stack) : -1;
        votesDown = hasVotes ? BookVoteCountsTag.down(stack) : -1;
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
        gfx.fill(left + DIM_X1, bookTop() + DIM_Y1, left + DIM_X2, bookTop() + DIM_Y2, DIM_COLOR);

        int centerX = left + PAGE_CENTER_X_OFFSET;
        // One of the reader's OWN books that the train has not released. It says where the book
        // stands INSTEAD of asking how they liked it — the news is the more useful thing, and asking
        // someone to rate their own unreleased writing is not a question worth putting to them. It
        // takes the question's place and nothing else: the author's own pages are untouched, and the
        // thumbs and the report icon below are exactly where they always are.
        Component status = UnapprovedBookMessage.forBook(moderation, bookType + ":" + bookId);
        if (status != null) {
            // Orange while an answer is still coming, red once it is not — and ordinary ink for one of
            // your own that is simply out on the line, which is news but not a warning.
            int statusColor = moderation == BookModerationState.DISLIKED ? COLOR_STATUS_REJECTED
                : moderation.isWithheld() ? COLOR_STATUS_WAITING : COLOR_TEXT;
            int sy = prefixY();
            for (FormattedCharSequence line : font.split(status, TEXT_WIDTH)) {
                gfx.drawString(font, line, centerX - font.width(line) / 2, sy, statusColor, false);
                sy += 9;
            }
        } else {
            // "The train asks," + this book's question, both centered in the page column.
            Component prefix = Component.translatable("gui.dungeontrain.book_vote.ask_prefix");
            gfx.drawString(font, prefix, centerX - font.width(prefix) / 2, prefixY(), COLOR_PREFIX, false);
            int y = promptY();
            Component prompt = Component.translatable("gui.dungeontrain.book_vote.prompt." + promptIndex);
            for (FormattedCharSequence line : font.split(prompt, TEXT_WIDTH)) {
                gfx.drawString(font, line, centerX - font.width(line) / 2, y, COLOR_TEXT, false);
                y += 9;
            }
        }

        // Thumbs — but never on a book you WROTE. The relay weights which books get served by player
        // votes and does not check authorship, so a shelf of your own writing plus a thumbs-up is a
        // self-upvoting machine. Rating your own work is also just not a question worth asking.
        if (!moderation.isOwn()) {
            boolean upLit = selectedVote == 1 || inUpButton(mouseX, mouseY);
            boolean downLit = selectedVote == -1 || inDownButton(mouseX, mouseY);
            gfx.blitSprite(upLit ? UP_HIGHLIGHTED_SPRITE : UP_SPRITE, upX(), buttonsY(), BUTTON_SIZE, BUTTON_SIZE);
            gfx.blitSprite(downLit ? DOWN_HIGHLIGHTED_SPRITE : DOWN_SPRITE, downX(), buttonsY(), BUTTON_SIZE, BUTTON_SIZE);

            Component yes = Component.translatable("gui.dungeontrain.book_vote.approve");
            Component no = Component.translatable("gui.dungeontrain.book_vote.reject");
            gfx.drawString(font, yes, upX() + BUTTON_SIZE / 2 - font.width(yes) / 2, labelsY(), COLOR_TEXT, false);
            gfx.drawString(font, no, downX() + BUTTON_SIZE / 2 - font.width(no) / 2, labelsY(), COLOR_TEXT, false);
        } else {
            renderVoteCounts(gfx, font);
        }

        renderAction(gfx, font, centerX, mouseX, mouseY);
    }

    /**
     * How this book is polling, in the row the thumbs used to occupy on somebody else's book.
     *
     * <p>The same two icons, drawn <b>inert</b> — no hover state and, crucially, no hitbox. They are
     * reporting an answer rather than asking a question, and the click handler must not grow a branch
     * for them: the voting hitboxes are gated behind {@code !moderation.isOwn()} precisely so that a
     * writer cannot vote on their own book, and putting a clickable icon back in that space would
     * undo it. Only the author ever sees these numbers — the relay hands vote tallies to nobody
     * else.</p>
     *
     * <p>Nothing is drawn when the relay never sent a tally ({@code -1}), nor on a book nothing could
     * have voted on yet: a pair of zeros under a book still awaiting its first read is dispiriting
     * and says nothing true. A book that WAS out and earned votes before being pulled still shows
     * what it earned.</p>
     */
    private static void renderVoteCounts(GuiGraphics gfx, Font font) {
        if (votesUp < 0 || votesDown < 0) return;
        if (moderation.isWithheld() && votesUp + votesDown == 0) return;

        gfx.blitSprite(UP_SPRITE, upX(), buttonsY(), BUTTON_SIZE, BUTTON_SIZE);
        gfx.blitSprite(DOWN_SPRITE, downX(), buttonsY(), BUTTON_SIZE, BUTTON_SIZE);

        Component up = Component.literal(Integer.toString(votesUp));
        Component down = Component.literal(Integer.toString(votesDown));
        gfx.drawString(font, up, upX() + BUTTON_SIZE / 2 - font.width(up) / 2, labelsY(), COLOR_TEXT, false);
        gfx.drawString(font, down, downX() + BUTTON_SIZE / 2 - font.width(down) / 2, labelsY(), COLOR_TEXT, false);
    }

    /**
     * The third control, in the row under the thumbs. Which one it is depends on whose book this is:
     *
     * <ul>
     *   <li>somebody else's → <b>Report</b>, unchanged;</li>
     *   <li>yours and released → <b>Make Private</b> / <b>Make Public</b>, a reversible toggle;</li>
     *   <li>yours and judged-but-withheld → <b>Protest</b>, which asks a person to look again and
     *       changes nothing by itself;</li>
     *   <li>yours and not yet read → <b>nothing</b>. There is no verdict to argue with.</li>
     * </ul>
     *
     * <p>Reporting your own book is nonsense and protesting somebody else's is not yours to do, so
     * they are alternatives rather than additions — one row, one control, always in the same place.</p>
     */
    private static void renderAction(GuiGraphics gfx, Font font, int centerX, int mouseX, int mouseY) {
        if (moderation.isWithheld()) {
            // ...but nothing at all on a book nothing has read yet: there is no verdict to protest.
            if (moderation.canProtest()) renderProtest(gfx, font, centerX, mouseX, mouseY);
            return;
        }
        if (moderation.isOwn()) {
            renderPrivate(gfx, font, centerX, mouseX, mouseY);
            return;
        }
        // The report icon — cream and wordless at rest, red on hover or once armed, dimmed and inert
        // once spent. Words appear only in the two states that need them (see below).
        boolean lit = !reported && (reportArmed || inReport(mouseX, mouseY));
        if (reported) gfx.setColor(1F, 1F, 1F, REPORTED_ALPHA);
        gfx.blitSprite(lit ? REPORT_HIGHLIGHTED_SPRITE : REPORT_SPRITE,
            reportX(), reportY(), BUTTON_SIZE, BUTTON_SIZE);
        if (reported) gfx.setColor(1F, 1F, 1F, 1F);

        // Idle and hover stay wordless — the icon is the whole control. The confirmation line is the
        // point of the second tap, and "Reported" is the only way a spent icon can say so.
        Component line = reported
            ? Component.translatable("gui.dungeontrain.book_vote.reported")
            : reportArmed ? Component.translatable("gui.dungeontrain.book_vote.report_confirm") : null;
        if (line != null) {
            gfx.drawString(font, line, centerX - font.width(line) / 2, reportTextY(),
                reported ? COLOR_REPORTED : COLOR_REPORT_ARMED, false);
        } else if (lit) {
            // Hovering an unlabelled icon: say what it does before the player commits to finding out.
            gfx.renderTooltip(font, Component.translatable("gui.dungeontrain.book_vote.report"),
                mouseX, mouseY);
        }
    }

    /**
     * Protest — the same two-tap shape as Report, because it is also a one-way claim put to a person,
     * and the same glyph, because it is the same gesture pointed the other way: Report says "this
     * book is wrong", Protest says "your verdict on it is". Only the wording differs, and they never
     * appear together.
     */
    private static void renderProtest(GuiGraphics gfx, Font font, int centerX, int mouseX, int mouseY) {
        boolean lit = !protested && (reportArmed || inReport(mouseX, mouseY));
        if (protested) gfx.setColor(1F, 1F, 1F, REPORTED_ALPHA);
        gfx.blitSprite(lit ? REPORT_HIGHLIGHTED_SPRITE : REPORT_SPRITE,
            reportX(), reportY(), BUTTON_SIZE, BUTTON_SIZE);
        if (protested) gfx.setColor(1F, 1F, 1F, 1F);

        Component line = protested
            ? Component.translatable("gui.dungeontrain.book_vote.protested")
            : reportArmed ? Component.translatable("gui.dungeontrain.book_vote.protest_confirm") : null;
        if (line != null) {
            gfx.drawString(font, line, centerX - font.width(line) / 2, reportTextY(),
                protested ? COLOR_REPORTED : COLOR_REPORT_ARMED, false);
        } else if (lit) {
            gfx.renderTooltip(font, Component.translatable("gui.dungeontrain.book_vote.protest"),
                mouseX, mouseY);
        }
    }

    /**
     * Make Private / Make Public — a single tap, no arming step. It is reversible, so a confirm on it
     * would be friction for nothing, and the label under the icon is always drawn: unlike Report there
     * is no "unspent" state to keep wordless, and a toggle that looks identical either way is a toggle
     * nobody can read.
     */
    private static void renderPrivate(GuiGraphics gfx, Font font, int centerX, int mouseX, int mouseY) {
        boolean lit = inReport(mouseX, mouseY);
        // Hovering shows the state the book will be in once clicked, not the one it is in — the
        // padlock closes under the pointer on a public book and springs open on a withdrawn one, so
        // the control previews its own effect. Away from the pointer it goes back to reporting the
        // truth about the book.
        boolean showLocked = lit != isPrivate;
        gfx.blitSprite(showLocked
                ? (lit ? PRIVATE_LOCKED_HIGHLIGHTED_SPRITE : PRIVATE_LOCKED_SPRITE)
                : (lit ? PRIVATE_UNLOCKED_HIGHLIGHTED_SPRITE : PRIVATE_UNLOCKED_SPRITE),
            reportX(), reportY(), BUTTON_SIZE, BUTTON_SIZE);
        Component line = Component.translatable(isPrivate
            ? "gui.dungeontrain.book_vote.private_on" : "gui.dungeontrain.book_vote.private_off");
        gfx.drawString(font, line, centerX - font.width(line) / 2, reportTextY(),
            isPrivate ? COLOR_REPORTED : COLOR_TEXT, false);
        if (lit) {
            gfx.renderTooltip(font, Component.translatable(isPrivate
                ? "gui.dungeontrain.book_vote.private_restore" : "gui.dungeontrain.book_vote.private_hint"),
                mouseX, mouseY);
        }
    }

    /** Thumb clicks on the vote page — instant commit (the screen closes, so consume the click). */
    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!active || event.getScreen() != screen || !onVotePage()) return;
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        int mx = (int) event.getMouseX();
        int my = (int) event.getMouseY();
        // The thumbs are not drawn on your own book, so they must not be clickable either — an
        // invisible hitbox that still votes is worse than the button being there.
        if (!moderation.isOwn() && inUpButton(mx, my)) {
            event.setCanceled(true);
            clickSound();
            applyVote(1);
        } else if (!moderation.isOwn() && inDownButton(mx, my)) {
            event.setCanceled(true);
            clickSound();
            applyVote(-1);
        } else if (inReport(mx, my) && moderation.isOwn() && !moderation.isWithheld()) {
            // Make Private / Make Public — single tap, and the screen stays open so the flipped
            // label is the feedback.
            event.setCanceled(true);
            clickSound();
            applyPrivate(!isPrivate);
        } else if (inReport(mx, my) && moderation.canProtest() && !protested) {
            // Two-tap, like a report: a one-way claim put to a person.
            event.setCanceled(true);
            clickSound();
            if (reportArmed) applyProtest(); else reportArmed = true;
        } else if (!moderation.isOwn() && !reported && inReport(mx, my)) {
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
        // ...but not on your own book, where there is no vote to cast. Without this the shortcut
        // would be a way round the missing thumbs, which is exactly the hole they close.
        if (moderation.isOwn()) return;
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

    /**
     * Commit a protest: send the packet (the server stamps the tag; the RELAY is what checks the
     * caller is actually this book's author), close the book, and have the train answer.
     *
     * <p>Nothing about the book changes. That is the honest thing to say in the response line too —
     * a protest asks a person to look again, it does not overturn anything, and a line implying
     * otherwise would be a promise the system does not keep.</p>
     */
    private static void applyProtest() {
        if (!active || protested) return;
        protested = true;
        reportArmed = false;
        DungeonTrainNet.sendToServer(new BookProtestPacket(bookType, bookId));

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        BookViewScreen closing = screen;
        if (mc.screen == closing) mc.setScreen(null); // triggers Closing → reset()

        if (player != null) {
            int pick = player.getRandom().nextInt(PROTEST_RESPONSE_COUNT) + 1;
            player.displayClientMessage(
                Component.translatable("gui.dungeontrain.book_vote.protest_response." + pick)
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    /**
     * Withdraw this book from circulation, or put it back.
     *
     * <p>The screen deliberately stays OPEN, unlike every other control here: a vote, a report and a
     * protest are one-shot verdicts that end the reading, while this is a setting the author may well
     * want to flip straight back. The label under the icon is the feedback.</p>
     */
    private static void applyPrivate(boolean makePrivate) {
        if (!active) return;
        isPrivate = makePrivate;
        reportArmed = false;
        DungeonTrainNet.sendToServer(new BookPrivatePacket(bookType, bookId, makePrivate));

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            int pick = player.getRandom().nextInt(PRIVATE_RESPONSE_COUNT) + 1;
            player.displayClientMessage(
                Component.translatable("gui.dungeontrain.book_vote."
                        + (makePrivate ? "private_response." : "public_response.") + pick)
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

    /**
     * Where the book is <b>actually</b> drawn, which is not always where vanilla puts it.
     *
     * <p>Scribble — bundled in the DT modpack, see {@link ScribbleColorPickerToggle} — ships
     * {@code centerBookGui} ON by default, and slides the whole book-view GUI down by
     * {@code (height - 192) / 3}: the background art, the page-turn and close buttons, and a matrix
     * translate around everything {@code BookViewScreen.render} draws. We draw from
     * {@code Render.Post}, which NeoForge fires <em>after</em> {@code render()} returns and therefore
     * after Scribble has popped that matrix — so nothing shifts us and we have to follow the book
     * ourselves. Left unhandled, the whole page detaches: at small offsets the train's line lands on
     * vanilla's "Page N of N" indicator, and at larger ones the thumbs are drawn over empty screen
     * while their hitboxes stay behind.</p>
     *
     * <p>Rather than ask Scribble — a soft dependency DT deliberately never links against — we read
     * the answer off vanilla: the back page-turn button, which
     * {@code createPageControlButtons()} fixes at {@link #VANILLA_PAGE_BUTTON_Y} and which any mod
     * that re-centres the book must have moved by exactly the same amount. That makes this correct
     * for re-centring mods generally, and an exact no-op ({@code bookTop() == BOOK_TOP}) when none is
     * installed.</p>
     */
    private static int bookTop() {
        PageButton back = ((BookViewScreenAccessor) (Object) screen).dungeontrain$getBackButton();
        return back == null ? BOOK_TOP : BOOK_TOP + back.getY() - VANILLA_PAGE_BUTTON_Y;
    }

    /** "The train asks," — or, on one of your own books, where it stands. */
    private static int prefixY() {
        return bookTop() + PREFIX_DY;
    }

    /** The question itself, under the prefix. */
    private static int promptY() {
        return bookTop() + PROMPT_DY;
    }

    /** The 👍/👎 row. */
    private static int buttonsY() {
        return bookTop() + BUTTONS_DY;
    }

    /** The (Y)es/(N)o labels — or the vote tallies on a book of your own. */
    private static int labelsY() {
        return bookTop() + LABELS_DY;
    }

    /** The third control's row: report / protest / withdraw. */
    private static int reportY() {
        return bookTop() + REPORT_DY;
    }

    /** Its confirmation or status line, when there is one. */
    private static int reportTextY() {
        return bookTop() + REPORT_TEXT_DY;
    }

    private static int upX() {
        return bookLeft() + PAGE_CENTER_X_OFFSET - BUTTON_SIZE - BUTTON_GAP / 2;
    }

    private static int downX() {
        return bookLeft() + PAGE_CENTER_X_OFFSET + BUTTON_GAP / 2;
    }

    private static boolean inUpButton(int x, int y) {
        return x >= upX() && x < upX() + BUTTON_SIZE && y >= buttonsY() && y < buttonsY() + BUTTON_SIZE;
    }

    private static boolean inDownButton(int x, int y) {
        return x >= downX() && x < downX() + BUTTON_SIZE && y >= buttonsY() && y < buttonsY() + BUTTON_SIZE;
    }

    /** The report icon sits on the page's centre line, under the two thumbs. */
    private static int reportX() {
        return bookLeft() + PAGE_CENTER_X_OFFSET - BUTTON_SIZE / 2;
    }

    private static boolean inReport(int x, int y) {
        return x >= reportX() && x < reportX() + BUTTON_SIZE
            && y >= reportY() && y < reportY() + BUTTON_SIZE;
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
        moderation = BookModerationState.PUBLIC;
        isPrivate = false;
        protested = false;
        votesUp = -1;
        votesDown = -1;
    }
}
