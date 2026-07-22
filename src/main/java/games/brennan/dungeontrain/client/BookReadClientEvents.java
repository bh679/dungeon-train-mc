package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.mixin.client.BookViewScreenAccessor;
import games.brennan.dungeontrain.narrative.NarrativeBookTag;
import games.brennan.dungeontrain.narrative.RandomBookTag;
import games.brennan.dungeontrain.narrative.SharedBookReadTag;
import games.brennan.dungeontrain.narrative.StartingBookTag;
import games.brennan.dungeontrain.net.BookReadClosedPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SharedBookReadSyncPacket;
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

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

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
        recordSharedReadGlobally();
        reset();
    }

    /**
     * Persist a community (player-written) book read into the GLOBAL client-side read history and top up
     * the server mirror, so the shared-book loot selector prefers books this player hasn't read — across
     * worlds and servers, and independent of the relay / network consent (the fallback path). Only fires
     * for {@code bookType == "shared"}; the relay-backed {@link BookReadClosedPacket} above (consent-gated)
     * remains the cross-machine source of truth. No-throw: a persistence/sync hiccup must not disturb the
     * read flow.
     */
    private static void recordSharedReadGlobally() {
        try {
            if (!"shared".equals(bookType) || bookId == null) return;
            int id = Integer.parseInt(bookId);
            if (id <= 0) return;
            // Persist to dungeontrain-client.toml (idempotent). Push the single id to the server so the
            // mirror stays current mid-session (e.g. read in world A then hop to world B this session).
            ClientDisplayConfig.markSharedRead(id);
            if (Minecraft.getInstance().getConnection() != null) {
                DungeonTrainNet.sendToServer(new SharedBookReadSyncPacket(java.util.List.of(id)));
            }
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] shared-book read persist/sync failed: {}", t.toString());
        }
    }

    /**
     * Attribute the elapsed time since the last sample to the page currently shown. Range check, NOT
     * a clamp: the virtual vote page (index == pageCount, appended by {@code BookVoteClientEvents})
     * sits past the real pages — its dwell must be dropped, not attributed to the last real page,
     * and visiting it (e.g. a Y/N hotkey jump from page 0) must never fake {@code completed=true}.
     */
    private static void accumulate() {
        int cur = ((BookViewScreenAccessor) (Object) tracked).dungeontrain$getCurrentPage();
        long now = System.nanoTime();
        if (cur >= 0 && cur < dwellMs.length) {
            dwellMs[cur] += (now - lastNanos) / 1_000_000L;
            if (cur > maxPage) maxPage = cur;
        }
        lastNanos = now;
    }

    /**
     * If {@code stack} is a DT book, set the identity fields ({@code bookType}/{@code bookId} and, for a
     * narrative, {@code story}/{@code letter}) and return the stack; otherwise return {@code null}.
     * Resolution (precedence + id shapes) is shared via {@link BookIdentity}.
     */
    private static ItemStack resolveIdentity(ItemStack stack) {
        Optional<BookIdentity> id = BookIdentity.resolve(stack);
        if (id.isEmpty()) return null;
        bookType = id.get().bookType();
        bookId = id.get().bookId();
        story = id.get().story();
        letter = id.get().letter();
        variantIndex = id.get().variantIndex();
        return stack;
    }

    /** Read display title/author + page count from the stack's written-book content (no page text kept). */
    private static void readContent(ItemStack stack) {
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) { pageCount = 0; return; }
        title = content.title().raw();
        author = content.author();
        pageCount = content.pages().size();
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
