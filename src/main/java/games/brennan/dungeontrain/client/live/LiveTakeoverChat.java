package games.brennan.dungeontrain.client.live;

import com.mojang.brigadier.arguments.StringArgumentType;
import games.brennan.dungeontrain.DungeonTrain;
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

    /** Logout / leaving the chat: forget the open vote. */
    static void reset() {
        open = null;
    }

    /** The line to print for a vote line from the room; it also tracks whether a vote is open. */
    static Component onPollLine(PollLine p, @Nullable UUID me) {
        if (p.open()) {
            open = p;
            return opening(p, me);
        }
        PollLine cur = open;
        if (cur != null && cur.id().equals(p.id())) open = null;
        return result(p);
    }

    private static Component opening(PollLine p, @Nullable UUID me) {
        MutableComponent line = Component.translatable(KEY + "poll", p.requester(), p.holder()).withStyle(ChatFormatting.GOLD)
            .append(" ").append(button("yes", ChatFormatting.GREEN))
            .append(" ").append(button("no", ChatFormatting.RED));
        boolean holder = me != null && p.holderUuid() != null && p.holderUuid().equalsIgnoreCase(me.toString().replace("-", ""));
        if (holder) line.append(" ").append(Component.translatable(KEY + "your_weight", p.holderWeight()).withStyle(ChatFormatting.GRAY));
        return LiveChatRules.systemLine(line);
    }

    private static Component result(PollLine p) {
        Component body = switch (p.state()) {
            case "approved" -> Component.translatable(KEY + "approved", Math.max(0, p.yes()), Math.max(0, p.no()), p.requester());
            case "rejected" -> Component.translatable(KEY + "rejected", Math.max(0, p.yes()), Math.max(0, p.no()), p.holder());
            default -> Component.translatable(KEY + "withdrawn", p.requester());
        };
        return LiveChatRules.systemLine(body.copy().withStyle(ChatFormatting.GOLD));
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
        LiveFeedClient.chatSend(LiveChatBridge.base(LiveStreamController.get().streaming()), me, mc.getUser().getName(), choice)
            .thenAcceptAsync(r -> {
                if (mc.player == null) return;
                String counted = r.ok() ? r.str("vote") : "";
                Component said;
                if ("yes".equals(counted) || "no".equals(counted)) {
                    said = Component.translatable(KEY + "voted", Component.translatable(KEY + "vote_" + counted));
                } else if ("requester".equals(r.str("voteReason"))) {
                    said = Component.translatable(KEY + "own_request");
                } else {
                    said = Component.translatable(KEY + "vote_not_counted");
                }
                mc.player.displayClientMessage(said.copy().withStyle(ChatFormatting.GRAY), false);
            }, mc);
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
