package games.brennan.dungeontrain.client.live;

import com.mojang.brigadier.arguments.StringArgumentType;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.ClientActionPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveTakeoverClient.PollLine;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * A takeover vote in the Live chat: its lines, and voting from the game.
 *
 * <p>The relay posts a vote's opening line into the room with the vote attached ({@link PollLine}); here
 * it prints with clickable <b>[Yes] [No]</b>, and the streamer being voted on is told how much their
 * vote counts. Votes are chat lines — {@code yes} / {@code no} — that the relay counts and keeps out of
 * the room. A vote typed in chat while one is open is taken off vanilla chat too, so players in the same
 * world don't see it either; only this player's own "vote recorded" line shows, until the result.</p>
 *
 * <p>A counted vote earns Every Vote Counts, and voting for the side that wins earns Called It — both
 * through {@link ClientActionPacket}, since only this client sees the relay's replies.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveTakeoverChat {

    static final String VOTE_COMMAND = "dungeontrain-live-vote";
    private static final String KEY = "chat.dungeontrain.live.takeover_";

    /** The open vote this client has seen, or null. */
    @Nullable private static volatile PollLine open;

    private LiveTakeoverChat() {}

    /** {@code yes} / {@code no} (or {@code !yes} / {@code !no}, a trailing ! or . allowed) → the vote; else null. */
    @Nullable
    static String voteOf(@Nullable String typed) {
        if (typed == null) return null;
        String t = typed.strip().toLowerCase(Locale.ROOT);
        if (t.startsWith("!")) t = t.substring(1);
        while (t.endsWith("!") || t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t.equals("yes") || t.equals("no") ? t : null;
    }

    /** A vote is open right now, as far as this client has seen. */
    static boolean voteOpen() {
        PollLine p = open;
        if (p == null) return false;
        if (System.currentTimeMillis() > p.endsTs() + 2_000) {
            open = null;
            return false;
        }
        return true;
    }

    /** When the open vote this client has seen closes, or −1 when none is open. */
    static long openVoteEndsMs() {
        PollLine p = open;
        return p != null && voteOpen() ? p.endsTs() : -1;
    }

    /** This player's last counted vote — the vote's id and {@code yes} / {@code no} — until its result, or null. */
    private record MyVote(String id, String choice) {}
    @Nullable private static volatile MyVote myVote;

    /** Logout / leaving the chat: forget the open vote. */
    static void reset() {
        open = null;
        myVote = null;
    }

    /** Whether {@code myChoice}, cast in vote {@code myId}, is the winning side of vote {@code id}. Pure for the tests. */
    static boolean votedWinner(@Nullable String myId, @Nullable String myChoice, String id, boolean approved) {
        return myId != null && myId.equals(id) && (approved ? "yes" : "no").equals(myChoice);
    }

    /** The vote whose result this client has printed, so the requester's own copy and the room's never both show. */
    @Nullable private static volatile String resultShown;

    /**
     * The line to print for a vote line from the room, or null when there is nothing to print (a result
     * already shown); it also tracks whether a vote is open.
     */
    @Nullable
    static Component onPollLine(PollLine p, @Nullable UUID me) {
        if (p.open()) {
            open = p;
            return opening(p, me);
        }
        PollLine cur = open;
        if (cur != null && cur.id().equals(p.id())) open = null;
        if (!"approved".equals(p.state()) && !"rejected".equals(p.state())) {
            return LiveChatRules.systemLine(Component.translatable(KEY + "withdrawn", p.requester()).withStyle(ChatFormatting.GOLD));
        }
        showResult(p.id(), "approved".equals(p.state()), p.yes(), p.no(), p.requester(), p.holder());
        return null;
    }

    /**
     * The verdict and the tally — "Takeover approved. Bob takes the Live Eye." then "5 - Yes | 2 - No" —
     * once per vote, whether it came from the room or the requester's own status poll.
     */
    static void showResult(String id, boolean approved, int yes, int no, String requester, String holder) {
        if (id.equals(resultShown)) return;
        resultShown = id;
        MyVote mine = myVote;
        if (mine != null && mine.id().equals(id)) {
            myVote = null;
            if (votedWinner(mine.id(), mine.choice(), id, approved)) sendAction("voted_live_takeover_winner");
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Component verdict = approved
            ? Component.translatable(KEY + "result_approved", requester)
            : Component.translatable(KEY + "result_rejected", holder);
        mc.gui.getChat().addMessage(LiveChatRules.systemLine(verdict.copy().withStyle(ChatFormatting.GOLD)));
        mc.gui.getChat().addMessage(LiveChatRules.systemLine(
            Component.translatable(KEY + "tally", Math.max(0, yes), Math.max(0, no)).withStyle(ChatFormatting.GOLD)));
    }

    private static Component opening(PollLine p, @Nullable UUID me) {
        MutableComponent line = Component.translatable(KEY + "poll", p.requester(), p.holder()).withStyle(ChatFormatting.GOLD);
        // The requester is not asked to vote on their own request: no buttons for them.
        Minecraft mc = Minecraft.getInstance();
        boolean mine = mc.getUser() != null && mc.getUser().getName().equalsIgnoreCase(p.requester());
        if (mine) return LiveChatRules.systemLine(line);
        boolean holder = me != null && p.holderUuid() != null && p.holderUuid().equalsIgnoreCase(me.toString().replace("-", ""));
        // Past their voting minutes the streamer is left out: no buttons, just why.
        if (holder && p.holderLocked()) {
            return LiveChatRules.systemLine(line.append(" ").append(Component.translatable(KEY + "holder_locked").withStyle(ChatFormatting.GRAY)));
        }
        line.append(" ").append(button("yes", ChatFormatting.GREEN))
            .append(" ").append(button("no", ChatFormatting.RED));
        if (holder) line.append(" ").append(Component.translatable(KEY + "your_weight", p.holderWeight()).withStyle(ChatFormatting.GRAY));
        return LiveChatRules.systemLine(line);
    }

    private static Component button(String choice, ChatFormatting colour) {
        return Component.translatable(KEY + "vote_" + choice).withStyle(style -> style
            .withColor(colour).withBold(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + VOTE_COMMAND + " " + choice))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(KEY + "vote_hover"))));
    }

    /** Send {@code choice} to the room as this player; the reply says whether it counted. */
    static void vote(String choice) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getUser() == null) return;
        UUID me = mc.getUser().getProfileId();
        PollLine poll = open;
        String pollId = poll == null ? null : poll.id();
        LiveFeedClient.chatSend(LiveChatBridge.base(LiveStreamController.get().streaming()), me, mc.getUser().getName(), choice)
            .thenAcceptAsync(r -> {
                if (mc.player == null) return;
                String counted = r.ok() ? r.str("vote") : "";
                Component said;
                if ("yes".equals(counted) || "no".equals(counted)) {
                    said = Component.translatable(KEY + "voted", Component.translatable(KEY + "vote_" + counted));
                    if (pollId != null) myVote = new MyVote(pollId, counted); // a changed vote replaces the first
                    sendAction("voted_live_takeover");
                } else if ("requester".equals(r.str("voteReason"))) {
                    said = Component.translatable(KEY + "own_request");
                } else if ("holder_locked".equals(r.str("voteReason"))) {
                    said = Component.translatable(KEY + "holder_locked_vote");
                } else {
                    said = Component.translatable(KEY + "vote_not_counted");
                }
                mc.player.displayClientMessage(said.copy().withStyle(ChatFormatting.GRAY), false);
            }, mc);
    }

    /** Fires a vote advancement on this player's server. Client thread. */
    private static void sendAction(String actionId) {
        if (Minecraft.getInstance().getConnection() != null) DungeonTrainNet.sendToServer(new ClientActionPacket(actionId));
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(VOTE_COMMAND)
            .then(Commands.argument("choice", StringArgumentType.word()).executes(ctx -> {
                String choice = voteOf(StringArgumentType.getString(ctx, "choice"));
                if (choice != null && voteOpen()) vote(choice);
                return 1;
            })));
    }
}
