package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.LiveModeration;
import games.brennan.dungeontrain.moderation.LiveModerationLadder;
import games.brennan.dungeontrain.net.relay.LiveModerationClient;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;

/**
 * {@code /liveappeal} — a player banned from the Live Eye by the text filter appeals one of the lines
 * that earned the ban.
 *
 * <ul>
 *   <li>{@code /liveappeal} — lists the lines (masked, numbered), each with a clickable [Appeal];</li>
 *   <li>{@code /liveappeal <n> [why]} — appeals line n (what the click runs), with an optional reason;</li>
 *   <li>{@code /liveappeal <why>} — a free-text appeal when no line is named.</li>
 * </ul>
 *
 * <p>One appeal per ban. It goes to the relay through the durable {@link RelayOutbox}; the relay keeps
 * every logged strike as the evidence, tells Brennan, and he approves or denies it on the explorer's
 * Appeals page. The relay's answer comes back through {@link LiveModeration#onAppealResponse} and is
 * said in chat. Player-facing ({@code requires(s -> true)}), and on {@code CommandAllowlist} so running
 * it never taints the run.</p>
 */
public final class LiveAppealCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String KEY = "command.dungeontrain.live_appeal.";

    private LiveAppealCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("liveappeal")
                .requires(s -> true)
                .executes(ctx -> run(ctx.getSource(), ""))
                .then(Commands.argument("line", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "line")))));
    }

    /** Pure: the line number at the front of the argument (1-based), or null when it is free text. */
    static Integer lineNumber(String arg) {
        if (arg == null) return null;
        String first = arg.strip().split("\\s+", 2)[0];
        if (first.isEmpty() || !first.chars().allMatch(Character::isDigit) || first.length() > 3) return null;
        return Integer.parseInt(first);
    }

    /** Pure: the reason after a leading line number, or the whole argument when there is none. */
    static String reason(String arg) {
        if (arg == null) return "";
        String s = arg.strip();
        if (lineNumber(s) == null) return s;
        String[] parts = s.split("\\s+", 2);
        return parts.length > 1 ? parts[1].strip() : "";
    }

    private static int run(CommandSourceStack source, String arg) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable(KEY + "player_only"));
            return 0;
        }
        if (!LiveModeration.isBanned(player.getUUID())) {
            source.sendFailure(Component.translatable(KEY + "not_banned"));
            return 0;
        }
        List<LiveModerationLadder.Evidence> evidence = LiveModeration.evidenceFor(player.getUUID());
        Integer n = lineNumber(arg);
        String why = reason(arg);

        // Bare /liveappeal: show the lines to pick from.
        if (n == null && why.isEmpty()) {
            if (evidence.isEmpty()) {
                source.sendFailure(Component.translatable(KEY + "usage"));
                return 0;
            }
            player.sendSystemMessage(Component.translatable(KEY + "pick").withStyle(ChatFormatting.GRAY)
                .append(LiveModeration.evidenceLines(evidence)));
            return 1;
        }

        Long evidenceId = null;
        if (n != null) {
            if (n < 1 || n > evidence.size()) {
                source.sendFailure(Component.translatable(KEY + "no_such_line", evidence.size()));
                return 0;
            }
            evidenceId = evidence.get(n - 1).id();
        }
        try {
            RelayOutbox.get().enqueue(LiveModerationClient.APPEAL_PATH,
                LiveModerationClient.appealBody(player.getUUID(), player.getGameProfile().getName(), evidenceId, why));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] /liveappeal could not be queued: {}", t.toString());
            source.sendFailure(Component.translatable(KEY + "failed"));
            return 0;
        }
        LOGGER.info("[DungeonTrain] /liveappeal by {} (line {}{})", player.getGameProfile().getName(),
            n == null ? "-" : n, why.isEmpty() ? "" : ", with a reason");
        source.sendSuccess(() -> Component.translatable(KEY + "sent").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
