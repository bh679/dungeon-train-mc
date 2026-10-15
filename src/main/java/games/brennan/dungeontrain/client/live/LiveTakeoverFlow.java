package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.LiveTakeoverClient;
import games.brennan.dungeontrain.net.relay.LiveTakeoverClient.Holder;
import games.brennan.dungeontrain.net.relay.LiveTakeoverClient.Poll;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The wearer's side of taking over the Live Eye: the prompt, the vote, and what comes of it.
 *
 * <p>{@link LiveStreamController} hands over here when its claim finds someone else on air
 * ({@link #blocked}). The Live Eye then works as a TV and {@link TakeoverRequestScreen} asks once.
 * <b>Request takeover</b> closes the prompt, opens a vote on the relay (its countdown shows over the
 * live box, {@link #voteCountdownSeconds}) and polls it every second: approved → claim with
 * the vote's ticket; rejected → the Live Eye becomes a TV; void (the holder left) → claim again.
 * <b>Take over</b> claims straight away. <b>Stay a viewer</b> gives the Live Eye up for a TV. One
 * request per Live Eye: once asked, the prompt does not come back.</p>
 *
 * <p>Render thread only, except the relay replies, which hop back onto it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveTakeoverFlow {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long POLL_MS = 1000;
    /** Give up on a vote the relay never closes (a restart lost it) this long after it should have. */
    private static final long VOTE_GRACE_MS = 15_000;

    @Nullable private static Holder holder;
    private static long holderReadMs;
    @Nullable private static TakeoverRequestScreen screen;
    /** This Live Eye has used its one request. */
    private static boolean requested;
    @Nullable private static String pollId;
    private static long voteEndsMs;
    private static long lastPollMs;
    private static final AtomicBoolean inFlight = new AtomicBoolean();

    private LiveTakeoverFlow() {}

    /** Another player's vote was running when the claim was blocked: ask again this long after it closes. */
    private static final long AFTER_OTHER_VOTE_MS = 1500;
    /** When to claim again because another player's vote was running; 0 = not waiting. */
    private static long retryAtMs;

    /**
     * The claim found {@code h} on air: show the prompt. While another player's takeover vote is running
     * there is nothing to ask yet — the Live Eye stays a TV and claims again once that vote has closed,
     * since its result may have changed who holds the channel.
     */
    static void blocked(Holder h) {
        holder = h;
        holderReadMs = System.currentTimeMillis();
        pollId = null;
        if (h.pollEndsMs() > holderReadMs) {
            retryAtMs = h.pollEndsMs() + AFTER_OTHER_VOTE_MS;
            return;
        }
        retryAtMs = 0;
        open();
    }

    /** This Live Eye has not used its one request yet (a vote that came back void still counts as used). */
    static boolean canRequest() {
        return !requested;
    }

    private static void open() {
        if (holder == null) return;
        Minecraft mc = Minecraft.getInstance();
        screen = new TakeoverRequestScreen(holder, holderReadMs);
        mc.setScreen(screen);
    }

    /**
     * Whole seconds left in a running takeover vote, for the countdown over the live box
     * ({@link LiveHeadViewer}); −1 when none is running. The requester's own vote, else one this client
     * saw open in Live Chat — the streamer being voted on and the viewers watching see it too.
     */
    static int voteCountdownSeconds() {
        long now = System.currentTimeMillis();
        if (pollId != null && LiveStreamController.get().state() == LiveStreamController.State.VOTING) {
            return (int) Math.max(0, (voteEndsMs - now + 999) / 1000);
        }
        long ends = LiveTakeoverChat.openVoteEndsMs();
        if (ends < 0 && retryAtMs > 0 && holder != null) ends = holder.pollEndsMs();
        return ends < 0 || ends < now - 1000 ? -1 : (int) Math.max(0, (ends - now + 999) / 1000);
    }

    /** Request takeover: open the vote. The prompt has closed; the countdown shows over the live box. */
    static void request() {
        screen = null;
        LiveStreamController c = LiveStreamController.get();
        UUID me = myUuid();
        if (me == null || !c.beginVote()) return;
        requested = true;
        String name = Minecraft.getInstance().getUser().getName();
        LiveTakeoverClient.request(me, name).thenAcceptAsync(r -> {
            if (c.state() != LiveStreamController.State.VOTING) return;
            Poll p = LiveTakeoverClient.parsePoll(r);
            if (p != null) {
                pollId = p.id();
                voteEndsMs = p.endsTs();
                lastPollMs = System.currentTimeMillis();
                return;
            }
            String error = r.str("error");
            if ("not_needed".equals(error)) {
                // The holder left or passed the protected minutes meanwhile: no vote needed.
                dismissScreen();
                c.retryClaim(true, null);
                return;
            }
            LOGGER.info("[DungeonTrain] takeover request refused: {} {}", r.status(), error);
            say(Component.translatable("busy".equals(error)
                ? "chat.dungeontrain.live.takeover_busy" : "chat.dungeontrain.live.takeover_unreachable").withStyle(ChatFormatting.GRAY));
            requested = false; // it never opened: the Live Eye keeps its request
            c.backToBlocked();
            open();
        }, Minecraft.getInstance());
    }

    /** Take over (past the protected minutes): claim now. */
    static void takeOver() {
        screen = null;
        LiveStreamController.get().retryClaim(true, null);
    }

    /** Stay a viewer: the Live Eye becomes a TV. */
    static void stayViewer() {
        screen = null;
        LiveStreamController.get().stayViewer();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LiveStreamController c = LiveStreamController.get();
        if (retryAtMs > 0 && System.currentTimeMillis() >= retryAtMs) {
            retryAtMs = 0;
            if (c.state() == LiveStreamController.State.BLOCKED) c.retryClaim(false, null);
        }
        if (c.state() != LiveStreamController.State.VOTING || pollId == null) return;
        long now = System.currentTimeMillis();
        if (now - lastPollMs < POLL_MS || !inFlight.compareAndSet(false, true)) return;
        lastPollMs = now;
        UUID me = myUuid();
        String id = pollId;
        if (me == null) { inFlight.set(false); return; }
        LiveTakeoverClient.status(id, me).thenAcceptAsync(r -> {
            inFlight.set(false);
            if (c.state() != LiveStreamController.State.VOTING || !id.equals(pollId)) return;
            Poll p = LiveTakeoverClient.parsePoll(r);
            if (p == null) {
                // Unreachable: keep asking until well past the end, then let it go as void.
                if (System.currentTimeMillis() > voteEndsMs + VOTE_GRACE_MS) settle(null);
                return;
            }
            if (p.open()) return;
            settle(p);
        }, Minecraft.getInstance()).exceptionally(t -> {
            inFlight.set(false);
            return null;
        });
    }

    /** The vote is over: approved → claim with the ticket; rejected → a TV; anything else → claim again. */
    private static void settle(@Nullable Poll p) {
        LiveStreamController c = LiveStreamController.get();
        pollId = null;
        // The requester sees the result even if their chat is mid-reconnect as the Live Eye changes.
        if (p != null && (p.approved() || p.rejected()) && holder != null) {
            String me = Minecraft.getInstance().getUser().getName();
            LiveTakeoverChat.showResult(p.id(), p.approved(), p.yes(), p.no(), me, holder.holder());
        }
        dismissScreen();
        if (p != null && p.approved() && p.ticket() != null) {
            c.retryClaim(false, p.ticket());
        } else if (p != null && p.rejected()) {
            c.takeoverRejected();
        } else {
            c.retryClaim(false, null);
        }
    }

    /** The stream ended some other way (taken off, died, logged out): withdraw a running vote, close the card. */
    static void abandon() {
        String id = pollId;
        UUID me = myUuid();
        if (id != null && me != null) LiveTakeoverClient.cancel(id, me);
        pollId = null;
        holder = null;
        retryAtMs = 0;
        dismissScreen();
    }

    /** A new Live Eye (a fresh START): it has its own request. */
    static void newEye() {
        requested = false;
        pollId = null;
        holder = null;
    }

    private static void dismissScreen() {
        TakeoverRequestScreen s = screen;
        screen = null;
        if (s != null) s.dismiss();
    }

    @Nullable
    private static UUID myUuid() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getUser() != null ? mc.getUser().getProfileId() : null;
    }

    private static void say(Component line) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(line, false);
    }
}
