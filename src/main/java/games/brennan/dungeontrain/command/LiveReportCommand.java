package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.moderation.LiveReport;
import games.brennan.dungeontrain.net.relay.LiveModerationClient;
import games.brennan.dungeontrain.net.relay.LiveModerationClient.ReportReply;
import games.brennan.dungeontrain.player.ServerLiveStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /livereport} — a player reports a Live Eye streamer for what they say or show.
 *
 * <ul>
 *   <li>{@code /livereport [reason]} — the one stream on air, or, with two, a [Stream 1] / [Stream 2] pick;
 *       without a reason, a reason picker;</li>
 *   <li>{@code /livereport stream <1|2> [reason]} — what the pick and picker buttons run;</li>
 *   <li>{@code /livereport abandon|keep} — the answer to "you already have an open report on them".</li>
 * </ul>
 *
 * <p>The report goes straight to the relay ({@link LiveModerationClient#sendReport}), off the server thread,
 * because its answer decides what is said: the relay keeps one open report per reporter and streamer and
 * answers a second with 409, which offers to abandon the first. The refused body is kept here for ten
 * minutes so [Abandon it and report again] carries no ids. Buttons run this server command, like
 * {@code /liveappeal}'s [Appeal]. Player-facing, hidden from tab completion ({@code HiddenCommands}) and on
 * {@code CommandAllowlist}, so running it never taints the run.</p>
 */
public final class LiveReportCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String KEY = "command.dungeontrain.live_report.";
    private static final long PENDING_MS = 10L * 60L * 1000L;

    /** A report the relay refused as a duplicate, waiting on abandon / keep. */
    private record Pending(String body, String streamerName, long atMs) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private LiveReportCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("livereport")
                .requires(s -> true)
                .executes(ctx -> report(ctx.getSource(), 0, ""))
                .then(Commands.literal("abandon").executes(ctx -> abandon(ctx.getSource())))
                .then(Commands.literal("keep").executes(ctx -> keep(ctx.getSource())))
                .then(Commands.literal("stream")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 2))
                                .executes(ctx -> report(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n"), ""))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> report(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n"),
                                            StringArgumentType.getString(ctx, "reason"))))))
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> report(ctx.getSource(), 0, StringArgumentType.getString(ctx, "reason")))));
    }

    /** {@code index} 0: whichever stream is on air (or ask); 1 / 2: that one. */
    private static int report(CommandSourceStack source, int index, String typed) {
        ServerPlayer player = playerOf(source);
        if (player == null) return 0;
        String name = player.getGameProfile().getName();
        List<LiveReport.Stream> live = ServerLiveStatus.liveStreams(source.getServer());
        LiveReport.Target target = LiveReport.resolve(live, player.getUUID(), name);
        String reason = LiveReport.reason(typed);

        LiveReport.Stream stream;
        if (index > 0) {
            stream = LiveReport.byIndex(live, index, player.getUUID(), name);
            if (stream == null) {
                // That stream ended, or it is the player's own: start over from what is on air now.
                if (target.kind() != LiveReport.Target.Kind.ONE && target.kind() != LiveReport.Target.Kind.PICK) {
                    return refuse(player, target);
                }
                return report(source, 0, typed);
            }
        } else {
            switch (target.kind()) {
                case NONE, SELF_ONLY -> {
                    return refuse(player, target);
                }
                case PICK -> {
                    player.sendSystemMessage(pickStream(target.streams(), reason));
                    return 1;
                }
                default -> stream = target.only();
            }
        }
        if (reason.isEmpty()) {
            player.sendSystemMessage(pickReason(stream));
            return 1;
        }
        String body = LiveModerationClient.reportBody(player.getUUID(), name, stream.uuid(), stream.name(),
            stream.channel(), reason, false);
        return send(player, body, stream.name());
    }

    private static int refuse(ServerPlayer player, LiveReport.Target target) {
        String key = target.kind() == LiveReport.Target.Kind.SELF_ONLY ? "self" : "nobody_live";
        player.sendSystemMessage(Component.translatable(KEY + key).withStyle(ChatFormatting.RED));
        return 0;
    }

    private static int abandon(CommandSourceStack source) {
        ServerPlayer player = playerOf(source);
        if (player == null) return 0;
        Pending p = takePending(player.getUUID());
        if (p == null) {
            player.sendSystemMessage(Component.translatable(KEY + "nothing_pending").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        return send(player, LiveModerationClient.withAbandon(p.body()), p.streamerName());
    }

    private static int keep(CommandSourceStack source) {
        ServerPlayer player = playerOf(source);
        if (player == null) return 0;
        Pending p = takePending(player.getUUID());
        if (p == null) {
            player.sendSystemMessage(Component.translatable(KEY + "nothing_pending").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        player.sendSystemMessage(Component.translatable(KEY + "kept", nameOr(p.streamerName())).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static Pending takePending(UUID player) {
        Pending p = PENDING.remove(player);
        return p == null || System.currentTimeMillis() - p.atMs() > PENDING_MS ? null : p;
    }

    /** POST the report; the answer is said in chat back on the server thread, if the player is still here. */
    private static int send(ServerPlayer player, String body, String streamerName) {
        UUID id = player.getUUID();
        if (!IN_FLIGHT.add(id)) {
            player.sendSystemMessage(Component.translatable(KEY + "busy").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        MinecraftServer server = player.getServer();
        LOGGER.info("[DungeonTrain] /livereport by {} on {}", player.getGameProfile().getName(), streamerName);
        LiveModerationClient.sendReport(body, reply -> {
            IN_FLIGHT.remove(id);
            if (server == null) return;
            server.execute(() -> {
                if (reply.kind() == ReportReply.Kind.OPEN) {
                    PENDING.put(id, new Pending(body, streamerName, System.currentTimeMillis()));
                }
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null) p.sendSystemMessage(replyLine(reply, streamerName, System.currentTimeMillis()));
            });
        });
        return 1;
    }

    /** The chat line for the relay's answer. */
    static Component replyLine(ReportReply reply, String streamerName, long nowMs) {
        Component who = nameOr(streamerName);
        return switch (reply.kind()) {
            case SENT -> Component.translatable(KEY + "sent", who).withStyle(ChatFormatting.GRAY);
            case OPEN -> {
                String earlier = reply.existingStreamerName().isEmpty() ? streamerName : reply.existingStreamerName();
                LiveReport.Ago ago = LiveReport.ago(reply.existingCreatedAt(), nowMs);
                Component when = Component.translatable(KEY + "ago." + ago.unit(), ago.amount());
                yield Component.translatable(KEY + "open", nameOr(earlier), when).withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(" "))
                    .append(button(KEY + "abandon", "/livereport abandon", ChatFormatting.GOLD))
                    .append(Component.literal(" "))
                    .append(button(KEY + "keep", "/livereport keep", ChatFormatting.GRAY));
            }
            case NOT_LIVE -> Component.translatable(KEY + "not_live", who).withStyle(ChatFormatting.RED);
            case BANNED -> Component.translatable(KEY + "banned").withStyle(ChatFormatting.RED);
            case RATE_LIMITED -> Component.translatable(KEY + "rate_limited").withStyle(ChatFormatting.RED);
            case FAILED -> Component.translatable(KEY + "failed").withStyle(ChatFormatting.RED);
        };
    }

    /** "Which stream?" with a button per stream; a typed reason rides along on each. */
    static Component pickStream(List<LiveReport.Stream> streams, String reason) {
        MutableComponent line = Component.translatable(KEY + "pick_stream").withStyle(ChatFormatting.GRAY);
        for (LiveReport.Stream s : streams) {
            Component label = s.name().isEmpty()
                ? Component.translatable(KEY + "stream_unnamed", s.index())
                : Component.translatable(KEY + "stream", s.index(), s.name());
            String cmd = "/livereport stream " + s.index() + (reason.isEmpty() ? "" : " " + reason);
            line.append(Component.literal(" ")).append(button(label, cmd, ChatFormatting.GOLD));
        }
        return line;
    }

    /** "Why are you reporting X?" with a button per preset reason, and the typed-reason hint. */
    static Component pickReason(LiveReport.Stream stream) {
        MutableComponent line = Component.translatable(KEY + "pick_reason", nameOr(stream.name())).withStyle(ChatFormatting.GRAY);
        for (String code : LiveReport.PRESET_REASONS) {
            line.append(Component.literal(" ")).append(button(KEY + "reason." + code,
                "/livereport stream " + stream.index() + " " + code, ChatFormatting.GOLD));
        }
        return line.append(Component.literal(" ")).append(Component.translatable(KEY + "reason_hint").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static Component nameOr(String name) {
        return name == null || name.isEmpty() ? Component.translatable(KEY + "unnamed") : Component.literal(name);
    }

    private static Component button(String key, String command, ChatFormatting colour) {
        return button(Component.translatable(key), command, colour);
    }

    private static Component button(Component label, String command, ChatFormatting colour) {
        return Component.literal("[").append(label).append("]")
            .withStyle(s -> s.withColor(colour).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    private static ServerPlayer playerOf(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable(KEY + "player_only"));
            return null;
        }
    }
}
