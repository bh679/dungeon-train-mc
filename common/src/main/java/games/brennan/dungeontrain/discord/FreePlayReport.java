package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DeathField;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;

/**
 * Mirrors a run's transition into <b>Free Play</b> to Discord: a short embed posted into the
 * player's own Discord thread the first time their run becomes "cheated" (creative/spectator,
 * a non-allowlisted cheat command, or joining a world already in creative).
 *
 * <p>Driven from the single chokepoint
 * {@link games.brennan.dungeontrain.cheat.RunIntegrity#markCheated}, which is idempotent, so
 * this fires exactly once per run/world regardless of which path tripped Free Play.</p>
 *
 * <p>We deliberately reuse Discord Presence's existing public
 * {@link DiscordService#postSurveyResponse} (embed-only, no composed image, routed into the
 * player's thread) so this ships entirely in the Dungeon Train repo — no Discord Presence
 * change or version bump. The trade-off is that the notice borrows the survey embed colour.</p>
 */
public final class FreePlayReport {

    private static final Logger LOGGER = LogUtils.getLogger();

    private FreePlayReport() {}

    /**
     * Best-effort: post the Free Play notice. Gated by
     * {@link DungeonTrainConfig#isFreePlayNoticeToDiscord()} and wrapped so a Discord hiccup can
     * never disrupt the Free Play transition that called it.
     *
     * @param cause the same soft localized phrase shown to the player in chat (e.g. "You
     *              switched to Creative.") — used as the embed description.
     */
    public static void post(ServerPlayer player, Component cause) {
        if (!DungeonTrainConfig.isFreePlayNoticeToDiscord()) return;
        try {
            String title = "🎮 " + player.getGameProfile().getName() + " entered Free Play";
            String description = cause.getString();
            List<DeathField> fields = List.of(new DeathField(
                    "Note", "Cross-world progress and global stats are paused for this run."));
            DiscordService.get().postSurveyResponse(player, title, description, fields);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] free-play notice to Discord failed: {}", t.toString());
        }
    }
}
