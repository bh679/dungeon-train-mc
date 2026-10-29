package games.brennan.dungeontrain.logrequest;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.compat.DiscordCommandHooks;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.discord.DiscordMarkdown;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;

/**
 * Tells the Developer that something he sent a player never reached them: "Message didn't land — why".
 *
 * <p>Used by the dev-message consent flow (a thread reply that can't be shown in-game) and by the
 * developer log request ({@code /bug}). When the request came in through a Discord thread, the
 * answer goes back as a plain reply into that same thread; otherwise it is posted as an embed into
 * the player's own thread. Best-effort: a Discord hiccup never disturbs the caller.</p>
 */
public final class DeliveryFailureReport {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Why a Developer message or request didn't land. The text is Brennan-facing (English only). */
    public enum Reason {
        KID_MODE("Kid mode is on"),
        DEV_CHAT_OFF("Developer chat is switched off"),
        NOT_IN_WORLD("they aren't in a world right now"),
        ALREADY_PENDING("a log request is already waiting on them"),
        TOO_SOON("a log request was sent less than a minute ago"),
        DECLINED("they declined to send their logs"),
        NO_ANSWER("they didn't answer the log request in time"),
        UPLOAD_FAILED("they approved, but their logs didn't upload; ask them to try /bug again");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    private DeliveryFailureReport() {}

    /** The reply line, e.g. {@code "Message didn't land (Steve) — Kid mode is on"}. */
    public static String line(@Nullable String playerName, Reason reason) {
        String who = playerName == null || playerName.isBlank() ? "" : " (" + DiscordMarkdown.escape(playerName) + ")";
        return "Message didn't land" + who + " — " + reason.text();
    }

    /**
     * Report a failure. {@code reply} is the Discord thread the request arrived in (null when it came
     * from elsewhere); {@code player} is the target, used to find their thread when {@code reply} is null.
     */
    public static void report(@Nullable DiscordCommandHooks.Reply reply, @Nullable ServerPlayer player,
                              @Nullable String playerName, Reason reason) {
        report(reply, player, playerName, reason, true);
    }

    /**
     * {@link #report(DiscordCommandHooks.Reply, ServerPlayer, String, Reason)}, Discord only when
     * {@code toDiscord}: a rate-limited livestream request that has already been reported is logged
     * and nothing more, so a looping client cannot turn the notice into a flood of posts.
     */
    public static void report(@Nullable DiscordCommandHooks.Reply reply, @Nullable ServerPlayer player,
                              @Nullable String playerName, Reason reason, boolean toDiscord) {
        String text = line(playerName, reason);
        if (!toDiscord) {
            LOGGER.info("[DungeonTrain] {} (already reported; not posted)", text);
            return;
        }
        try {
            if (reply != null) {
                reply.send(text);
            } else if (player != null) {
                DiscordService.get().postSurveyResponse(player, "⚠️ " + text, "", List.of());
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] delivery-failure notice to Discord failed: {}", t.toString());
        }
        LOGGER.info("[DungeonTrain] {}", text);
    }
}
