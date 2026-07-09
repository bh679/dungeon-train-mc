package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.BookViewScreenAccessor;
import games.brennan.dungeontrain.narrative.NarrativeBookTag;
import games.brennan.dungeontrain.narrative.RandomBookTag;
import games.brennan.dungeontrain.narrative.SharedBookReadTag;
import games.brennan.dungeontrain.narrative.StartingBookTag;
import games.brennan.dungeontrain.net.BookReadClosedPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Client-side measurement of how Dungeon Train books are READ, for the data-explorer's Books page.
 *
 * <p>Vanilla {@code BookViewScreen} is a pure-client screen (opening / paging / closing never reach the
 * server), so timing can only be captured here. When the player opens a DT book held in hand — a random
 * loot book ({@link RandomBookTag}), a discovered community book ({@link SharedBookReadTag}), a
 * narrative letter ({@link NarrativeBookTag}), or a welcome/starting book ({@link StartingBookTag}) —
 * this starts a timer, samples the current page each client tick to build a per-page dwell breakdown,
 * and on close sends a {@link BookReadClosedPacket} so the server can consent-gate + report it.
 * Metadata + timings only — page text is never sent.</p>
 *
 * <p><b>Held books only.</b> Lectern reads open a {@code LecternScreen} (a {@code BookViewScreen}
 * subclass) whose book isn't in hand, so they're deliberately excluded — a held DT book resolves its
 * identity from the hand slot with no ambiguity. Lectern-read telemetry is a possible follow-up.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BookReadClientEvents {

    /** Vanilla book & quill / written book page cap; bounds the dwell array. */
    private static final int MAX_PAGES = 100;

    // --- single in-flight read (one book screen is open at a time) ---
    private static boolean tracking = false;
    private static BookViewScreen tracked = null;
    private static long openNanos = 0L;
    private static long lastNanos = 0L;
    private static long[] dwellMs = null; // ms accumulated per page index
    private static int maxPage = 0;

    private static String bookType = null;
    private static String bookId = null;
    private static String title = "";
    private static String author = "";
    private static int pageCount = 0;
    private static String story = "";
    private static int letter = 0;
    private static int variantIndex = -1; // random books only; -1 = not applicable / unresolved

    private BookReadClientEvents() {}

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        reset(); // any previous read closed via onScreenClosing; clear defensively
        Screen screen = event.getScreen();
        // Held-book reads only: a LecternScreen is a BookViewScreen but its book isn't in hand.
        if (!(screen instanceof BookViewScreen book) || screen instanceof LecternScreen) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack stack = resolveIdentity(player.getMainHandItem());
        if (stack == null) stack = resolveIdentity(player.getOffhandItem());
        if (stack == null) return; // not a DT book in hand → don't track

        readContent(stack);
        if (pageCount <= 0) { reset(); return; }
        dwellMs = new long[Math.min(pageCount, MAX_PAGES)];
        maxPage = 0;
        openNanos = System.nanoTime();
        lastNanos = openNanos;
        tracked = book;
        tracking = true;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!tracking) return;
        if (Minecraft.getInstance().screen != tracked) return; // safety: only while our screen is up
        accumulate();
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (!tracking || event.getScreen() != tracked) return;
        accumulate(); // flush the final visible page
        long durationMs = (System.nanoTime() - openNanos) / 1_000_000L;

        List<Integer> dwell = new ArrayList<>(dwellMs.length);
        int pagesViewed = 0;
        for (long ms : dwellMs) {
            dwell.add((int) Math.min(ms, Integer.MAX_VALUE));
            if (ms > 0) pagesViewed++;
        }
        boolean completed = pageCount > 0 && maxPage >= pageCount - 1;

        DungeonTrainNet.sendToServer(new BookReadClosedPacket(
            bookType, bookId, title, author, pageCount, pagesViewed, maxPage, completed,
            Math.max(0, durationMs), dwell, story, letter, variantIndex));
        reset();
    }

    /** Attribute the elapsed time since the last sample to the page currently shown. */
    private static void accumulate() {
        int cur = clamp(((BookViewScreenAccessor) (Object) tracked).dungeontrain$getCurrentPage(),
            0, dwellMs.length - 1);
        long now = System.nanoTime();
        dwellMs[cur] += (now - lastNanos) / 1_000_000L;
        lastNanos = now;
        if (cur > maxPage) maxPage = cur;
    }

    /**
     * If {@code stack} is a DT book, set the identity fields ({@code bookType}/{@code bookId} and, for a
     * narrative, {@code story}/{@code letter}) and return the stack; otherwise return {@code null}.
     * Precedence random → narrative → shared → starting is arbitrary but the four tag sets never co-occur.
     */
    private static ItemStack resolveIdentity(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Optional<RandomBookTag.RandomBookIdentity> rnd = RandomBookTag.read(stack);
        if (rnd.isPresent()) {
            bookType = "random";
            bookId = rnd.get().basename();
            variantIndex = rnd.get().variantIndex();
            return stack;
        }
        Optional<NarrativeBookTag.NarrativeIdentity> nar = NarrativeBookTag.read(stack);
        if (nar.isPresent()) {
            bookType = "narrative";
            story = nar.get().storyBasename();
            letter = nar.get().letterIndex();
            bookId = story + "#" + letter;
            return stack;
        }
        OptionalInt shared = SharedBookReadTag.readId(stack);
        if (shared.isPresent()) {
            bookType = "shared";
            bookId = Integer.toString(shared.getAsInt());
            return stack;
        }
        Optional<StartingBookTag.StartingBookIdentity> starting = StartingBookTag.read(stack);
        if (starting.isPresent()) {
            bookType = "starting";
            bookId = starting.get().basename();
            variantIndex = starting.get().variantIndex();
            return stack;
        }
        return null;
    }

    /** Read display title/author + page count from the stack's written-book content (no page text kept). */
    private static void readContent(ItemStack stack) {
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) { pageCount = 0; return; }
        title = content.title().raw();
        author = content.author();
        pageCount = content.pages().size();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void reset() {
        tracking = false;
        tracked = null;
        dwellMs = null;
        maxPage = 0;
        bookType = null;
        bookId = null;
        title = "";
        author = "";
        pageCount = 0;
        story = "";
        letter = 0;
        variantIndex = -1;
    }
}
