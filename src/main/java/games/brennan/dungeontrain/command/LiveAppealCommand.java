package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.LiveModeration;
import games.brennan.dungeontrain.net.relay.LiveModerationClient;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * {@code /liveappeal <reason>} — a player banned from the Live Eye by the text filter asks for the ban
 * to be lifted. One appeal per ban. The reason goes to the relay through the durable
 * {@link RelayOutbox}; the relay keeps every logged strike as the evidence, tells Brennan, and he
 * approves or denies it on the explorer's Live appeals page. The relay's answer comes back through
 * {@link LiveModeration#onAppealResponse} and is said in chat.
 *
 * <p>Player-facing ({@code requires(s -> true)}) like {@code /reportcarriage}, and on
 * {@code CommandAllowlist} so running it never taints the run.</p>
 */
public final class LiveAppealCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String KEY = "command.dungeontrain.live_appeal.";

    private LiveAppealCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("liveappeal")
                .requires(s -> true)
                .executes(ctx -> usage(ctx.getSource()))
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "reason")))));
    }

    private static int usage(CommandSourceStack source) {
        source.sendFailure(Component.translatable(KEY + "usage"));
        return 0;
    }

    private static int run(CommandSourceStack source, String reason) {
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
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.isEmpty()) return usage(source);
        try {
            RelayOutbox.get().enqueue(LiveModerationClient.APPEAL_PATH,
                LiveModerationClient.appealBody(player.getUUID(), player.getGameProfile().getName(), trimmed));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] /liveappeal could not be queued: {}", t.toString());
            source.sendFailure(Component.translatable(KEY + "failed"));
            return 0;
        }
        LOGGER.info("[DungeonTrain] /liveappeal by {}", player.getGameProfile().getName());
        source.sendSuccess(() -> Component.translatable(KEY + "sent").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
