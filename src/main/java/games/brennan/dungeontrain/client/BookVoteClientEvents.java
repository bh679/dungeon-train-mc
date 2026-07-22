package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.BookViewScreenAccessor;
import games.brennan.dungeontrain.narrative.BookVoteTag;
import games.brennan.dungeontrain.net.BookVotePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
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
 * The 👍/👎 vote page — a VIRTUAL page appended after the last real page of every votable DT book
 * (the four identities {@link BookIdentity} resolves; deathnotes / letters / player-written books
 * have none of those tags and are untouched). Zero mixins and zero stack mutation: on
 * {@code Init.Post} the screen's public {@link BookViewScreen#setBookAccess} is handed a copy of the
 * real pages plus one heading page, so the vanilla forward PageButton, page indicator and page-turn
 * flow all "discover" the extra page on their own — old-world books get it for free, and
 * relay-submitted shared-book content/hashes are untouched.
 *
 * <p>Two thumb buttons ({@link BookVoteButton}, vanilla PageButton styling) sit on the vote page
 * with labels underneath (green/red when that side is the current vote); <b>Y</b>/<b>N</b> work from
 * ANY page — jumping to the vote page with the page-turn sound, then casting. Casting sends a
 * {@link BookVotePacket}; the server re-validates the held stack and stamps {@link BookVoteTag}, so
 * reopening seeds the selected state from the stack itself. Closing (or throwing) without voting
 * registers nothing. Lectern reads are excluded, same guard as read telemetry
 * ({@link BookReadClientEvents}) — which also range-checks the vote page out of its dwell math.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BookVoteClientEvents {

    // Book background geometry (BookViewScreen): 192px art anchored at ((width-192)/2, 2); the page
    // column is 114px wide starting 36px in, so the page's center X is left + 93.
    private static final int PAGE_CENTER_X_OFFSET = 93;
    private static final int BUTTONS_Y = 92;
    private static final int BUTTON_GAP = 20;            // between the two thumbs
    private static final int LABELS_Y = BUTTONS_Y + BookVoteButton.SIZE + 6;
    private static final int HINT_Y = LABELS_Y + 18;

    private static final int COLOR_LABEL = 0x000000;     // vanilla book-text black
    private static final int COLOR_APPROVED = 0x2E7D32;  // green when 👍 is the current vote
    private static final int COLOR_REJECTED = 0xB02E26;  // red when 👎 is the current vote
    private static final int COLOR_HINT = 0x666666;

    // --- single tracked votable book screen (one book screen is open at a time) ---
    private static boolean active = false;
    private static BookViewScreen screen = null;
    private static String bookType = null;
    private static String bookId = null;
    private static int variantIndex = -1;
    private static List<Component> realPages = null;     // the book's REAL pages (vote page excluded)
    private static int selectedVote = 0;                 // 0 none, ±1 — seeded from the stack's tag
    private static BookVoteButton upButton = null;
    private static BookVoteButton downButton = null;

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
        screen = book;
        active = true;
    }

    /**
     * Swap in the vote page + add the thumb buttons. {@code Init.Post} re-fires on every resize with
     * the widget list cleared, so both steps are rebuilt from {@link #realPages} each time.
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!active || event.getScreen() != screen) return;

        List<Component> pages = new ArrayList<>(realPages);
        pages.add(Component.translatable("gui.dungeontrain.book_vote.page_heading"));
        screen.setBookAccess(new BookViewScreen.BookAccess(pages));

        int centerX = (screen.width - 192) / 2 + PAGE_CENTER_X_OFFSET;
        upButton = new BookVoteButton(centerX - BookVoteButton.SIZE - BUTTON_GAP / 2, BUTTONS_Y,
            true, () -> selectedVote == 1, b -> applyVote(1),
            Component.translatable("gui.dungeontrain.book_vote.approve.narration"));
        downButton = new BookVoteButton(centerX + BUTTON_GAP / 2, BUTTONS_Y,
            false, () -> selectedVote == -1, b -> applyVote(-1),
            Component.translatable("gui.dungeontrain.book_vote.reject.narration"));
        upButton.visible = false;
        downButton.visible = false;
        event.addListener(upButton);
        event.addListener(downButton);
    }

    /** The thumbs only exist on the vote page — {@code visible} gates rendering AND clicks. */
    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (!active || event.getScreen() != screen || upButton == null) return;
        boolean onVotePage = onVotePage();
        upButton.visible = onVotePage;
        downButton.visible = onVotePage;
    }

    /** Labels under the thumbs (colored when that side is the current vote) + the hotkey hint. */
    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!active || event.getScreen() != screen || upButton == null || !onVotePage()) return;
        GuiGraphics gfx = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int centerX = (screen.width - 192) / 2 + PAGE_CENTER_X_OFFSET;

        Component approve = Component.translatable("gui.dungeontrain.book_vote.approve");
        Component reject = Component.translatable("gui.dungeontrain.book_vote.reject");
        int upCenter = upButton.getX() + BookVoteButton.SIZE / 2;
        int downCenter = downButton.getX() + BookVoteButton.SIZE / 2;
        gfx.drawString(font, approve, upCenter - font.width(approve) / 2, LABELS_Y,
            selectedVote == 1 ? COLOR_APPROVED : COLOR_LABEL, false);
        gfx.drawString(font, reject, downCenter - font.width(reject) / 2, LABELS_Y,
            selectedVote == -1 ? COLOR_REJECTED : COLOR_LABEL, false);

        Component hint = Component.translatable("gui.dungeontrain.book_vote.hint");
        gfx.drawString(font, hint, centerX - font.width(hint) / 2, HINT_Y, COLOR_HINT, false);
    }

    /** Y/N from ANY page: jump to the vote page (page-turn sound) and cast. */
    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Post event) {
        if (!active || event.getScreen() != screen) return;
        int key = event.getKeyCode();
        if (key != GLFW.GLFW_KEY_Y && key != GLFW.GLFW_KEY_N) return;
        if (!onVotePage()) {
            screen.setPage(realPages.size());
            Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0F));
        }
        applyVote(key == GLFW.GLFW_KEY_Y ? 1 : -1);
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() == screen) reset();
    }

    /** Cast (or change) the vote: light the button locally, let the server validate + stamp + relay. */
    private static void applyVote(int vote) {
        if (!active || (vote != 1 && vote != -1)) return;
        selectedVote = vote;
        DungeonTrainNet.sendToServer(new BookVotePacket(bookType, bookId, vote, variantIndex));
    }

    private static boolean onVotePage() {
        return ((BookViewScreenAccessor) (Object) screen).dungeontrain$getCurrentPage() >= realPages.size();
    }

    /** {@code stack} when it resolves to a votable DT book (sets the identity fields), else null. */
    private static ItemStack votable(ItemStack stack) {
        Optional<BookIdentity> id = BookIdentity.resolve(stack);
        if (id.isEmpty()) return null;
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
        upButton = null;
        downButton = null;
    }
}
