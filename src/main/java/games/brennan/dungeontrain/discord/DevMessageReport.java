package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DeathField;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;

/**
 * Posts the two dev-message-consent notices to Discord: one when the in-game consent prompt is
 * shown to a player ("consent requested"), and one when the player accepts ("consent accepted").
 * Both post into that player's own Discord thread.
 *
 * <p>Reuses Discord Presence's existing public {@link DiscordService#postSurveyResponse} (the same
 * embed-only, thread-routed primitive {@link FreePlayReport} uses) so this ships entirely in the
 * Dungeon Train repo — no Discord Presence change. Gated by
 * {@link DungeonTrainConfig#isDevMessageConsentToDiscord()} and wrapped so a Discord hiccup can
 * never disrupt the consent flow that called it.</p>
 */
public final class DevMessageReport {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DevMessageReport() {}

    /** Best-effort: the in-game consent prompt was shown to {@code player}. */
    public static void postConsentRequested(ServerPlayer player) {
        if (!DungeonTrainConfig.isDevMessageConsentToDiscord()) return;
        try {
            String title = "✉️ Consent requested — " + player.getGameProfile().getName();
            String description = "The Developer has a message waiting; the in-game consent prompt was shown.";
            List<DeathField> fields = List.of(new DeathField(
                    "Next", "The message appears in their in-game chat once they type @Dev to accept."));
            DiscordService.get().postSurveyResponse(player, title, description, fields);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] dev-message consent-requested notice to Discord failed: {}", t.toString());
        }
    }

    /** Best-effort: {@code player} accepted the Developer's message. */
    public static void postConsentAccepted(ServerPlayer player) {
        if (!DungeonTrainConfig.isDevMessageConsentToDiscord()) return;
        try {
            String title = "✅ Consent accepted — " + player.getGameProfile().getName();
            String description = "They typed @Dev; the held Developer message(s) were delivered to their in-game chat.";
            DiscordService.get().postSurveyResponse(player, title, description, List.of());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] dev-message consent-accepted notice to Discord failed: {}", t.toString());
        }
    }
}
