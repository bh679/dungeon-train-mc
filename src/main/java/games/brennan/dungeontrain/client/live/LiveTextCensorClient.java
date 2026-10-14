package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.mixin.client.BookEditScreenAccessor;
import games.brennan.dungeontrain.mixin.client.BookEditScreenInvoker;
import games.brennan.dungeontrain.mixin.client.ChatScreenAccessor;
import games.brennan.dungeontrain.moderation.LiveModerationScope;
import games.brennan.dungeontrain.moderation.LiveWordList;
import games.brennan.dungeontrain.moderation.PatternCensor;
import games.brennan.dungeontrain.moderation.TextCensor;
import games.brennan.dungeontrain.net.LiveModerationStrikePacket;
import games.brennan.dungeontrain.net.LiveModerationStrikePacket.Source;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Client half of the Live Eye text filter: the words never reach the screen, let alone the stream.
 *
 * <p>Three hooks, all local and synchronous ({@link TextCensor} on a few hundred characters):</p>
 * <ul>
 *   <li><b>as you type in chat</b> — after every key or character in the {@link ChatScreen}, a listed
 *       word that has just been completed is replaced in the box with {@code ****};</li>
 *   <li><b>as you type in a book</b> — the same on the current page and the title of a
 *       {@link BookEditScreen}, while this client is the one streaming;</li>
 *   <li><b>as a chat line leaves</b> — {@link ClientChatEvent} at {@link EventPriority#HIGHEST}, ahead
 *       of {@link LiveChatBridge}, so the signed vanilla packet and the relay's Twitch/Discord chat
 *       both carry the censored line.</li>
 * </ul>
 *
 * <p>The server never sees the word, so each catch is reported with a {@link LiveModerationStrikePacket}
 * (one per {@link #REPORT_GAP_MS}); the server counts the strike and says the warning. Scope follows
 * {@link LiveModerationScope}: this client streaming, or in the stream's chat room.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveTextCensorClient {

    /** One report per outburst; the server applies the same gap. */
    static final long REPORT_GAP_MS = 10_000L;

    private static long lastReportMs;
    private static boolean rewriting;

    private LiveTextCensorClient() {}

    // ---- the line as it leaves ------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientChat(ClientChatEvent event) {
        try {
            if (!chatActive()) return;
            TextCensor.Result r = TextCensor.censor(event.getMessage(), LiveWordList.effective());
            TextCensor.Result p = PatternCensor.censor(r.text());
            if (!r.changed() && !p.changed()) return;
            event.setMessage(p.text());
            if (r.changed()) report(Source.CHAT, event.getOriginalMessage(), r.hits());
            if (p.changed()) warnLinkHidden();
        } catch (Throwable t) {
            // Fail open: never eat the player's line over a filter fault.
        }
    }

    // ---- as you type ----------------------------------------------------------------------------

    @SubscribeEvent
    public static void onCharTyped(ScreenEvent.CharacterTyped.Post event) {
        checkField(event.getScreen());
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Post event) {
        checkField(event.getScreen());
    }

    private static void checkField(Screen screen) {
        if (rewriting) return;
        try {
            rewriting = true;
            if (screen instanceof ChatScreen chat) {
                if (chatActive()) checkChatBox(chat);
            } else if (screen instanceof BookEditScreen book) {
                if (bookActive()) checkBook(book);
            }
        } catch (Throwable t) {
            // Fail open.
        } finally {
            rewriting = false;
        }
    }

    private static void checkChatBox(ChatScreen chat) {
        EditBox input = ((ChatScreenAccessor) chat).dungeontrain$getInput();
        if (input == null) return;
        String typed = input.getValue();
        if (typed.isEmpty() || typed.startsWith("/")) return;
        TextCensor.Result r = TextCensor.censorTail(typed, LiveWordList.effective());
        TextCensor.Result p = PatternCensor.censorTail(r.text());
        if (!r.changed() && !p.changed()) return;
        input.setValue(p.text());
        if (r.changed()) report(Source.CHAT_FIELD, typed, r.hits());
        if (p.changed()) warnLinkHidden();
    }

    private static void checkBook(BookEditScreen book) {
        BookEditScreenAccessor acc = (BookEditScreenAccessor) book;
        BookEditScreenInvoker inv = (BookEditScreenInvoker) book;
        var words = LiveWordList.effective();
        if (acc.dungeontrain$isSigning()) {
            String title = acc.dungeontrain$getTitle();
            TextCensor.Result r = TextCensor.censorTail(title == null ? "" : title, words);
            TextCensor.Result p = PatternCensor.censorTail(r.text());
            if (r.changed() || p.changed()) {
                acc.dungeontrain$setTitle(p.text());
                acc.dungeontrain$getTitleEdit().setCursorToEnd();
                if (r.changed()) report(Source.BOOK_FIELD, "[title] " + title, r.hits());
                if (p.changed()) warnLinkHidden();
            }
        } else {
            List<String> pages = acc.dungeontrain$getPages();
            int page = acc.dungeontrain$getCurrentPage();
            if (page < 0 || page >= pages.size()) return;
            String typed = pages.get(page);
            TextCensor.Result r = TextCensor.censorTail(typed, words);
            TextCensor.Result p = PatternCensor.censorTail(r.text());
            if (r.changed() || p.changed()) {
                inv.dungeontrain$setCurrentPageText(p.text());
                acc.dungeontrain$getPageEdit().setCursorToEnd();
                inv.dungeontrain$clearDisplayCache();
                if (r.changed()) report(Source.BOOK_FIELD, typed, r.hits());
                if (p.changed()) warnLinkHidden();
            }
        }
    }

    // ---- scope + report -------------------------------------------------------------------------

    private static boolean chatActive() {
        return LiveModerationScope.clientActive(LiveStreamController.get().streaming(), LiveChatBridge.connected());
    }

    /** A book is on stream only on the writer's own stream; prop books in an editor plot are authored content. */
    private static boolean bookActive() {
        if (!LiveModerationScope.bookFieldActive(LiveStreamController.get().streaming())) return false;
        try {
            return !EditorStatusHudOverlay.isActive();
        } catch (Throwable t) {
            return true;
        }
    }

    private static long lastLinkWarnMs;

    /** A link or phone number was hidden: say so locally, at most once per 10 s. Never a strike. */
    private static void warnLinkHidden() {
        long now = System.currentTimeMillis();
        if (now - lastLinkWarnMs < REPORT_GAP_MS) return;
        lastLinkWarnMs = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.translatable("chat.dungeontrain.live.link_hidden")
                .withStyle(net.minecraft.ChatFormatting.GRAY), false);
        }
    }

    /** Tell the server, with the line as typed and the terms that matched (the relay's evidence). */
    private static void report(Source source, String typed, List<String> hits) {
        long now = System.currentTimeMillis();
        if (now - lastReportMs < REPORT_GAP_MS) return;
        lastReportMs = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) PacketDistributor.sendToServer(new LiveModerationStrikePacket(source, typed, hits));
    }
}
