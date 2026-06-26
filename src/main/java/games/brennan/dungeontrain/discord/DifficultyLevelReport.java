package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DeathField;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Posts a short embed into the player's Discord thread each time their difficulty
 * tier increases. Gated by {@link DungeonTrainConfig#isDifficultyLevelNoticeToDiscord()}.
 *
 * <p>Fires once per tier per life — tier resets to 0 on death, so a player who reaches
 * tier 3, dies, and advances again will see fresh notifications in the new life.</p>
 *
 * <p>Reuses Discord Presence's existing
 * {@link DiscordService#postSurveyResponse} (embed-only, routes into the player's
 * thread) so no Discord Presence bump is required.</p>
 */
public final class DifficultyLevelReport {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DifficultyLevelReport() {}

    /**
     * Best-effort: post the difficulty-level notice. Wrapped so a Discord hiccup
     * never disrupts the boarding-progress tick that called it.
     */
    public static void post(ServerPlayer player, int tier) {
        if (!DungeonTrainConfig.isDifficultyLevelNoticeToDiscord()) return;
        try {
            String title = "⚔ " + player.getGameProfile().getName() + " reached Difficulty Level " + tier;
            String description = "Enemies are toughening up.";
            List<DeathField> fields = new ArrayList<>();
            Integer carriage = TrainCarriageAppender.lastCarriageIndex(player.getUUID());
            if (carriage != null) {
                fields.add(new DeathField("Carriage", formatSigned(carriage)));
            }
            DiscordService.get().postSurveyResponse(player, title, description, fields);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] difficulty-level notice to Discord failed: {}", t.toString());
        }
    }

    private static String formatSigned(int n) {
        return n > 0 ? "+" + n : Integer.toString(n);
    }
}
