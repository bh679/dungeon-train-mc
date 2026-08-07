package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DeathField;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.net.ModRecommendPacket;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Delivers a mod recommendation from the death screen's Mod Recommendations page, splitting it
 * deliberately across two destinations:
 *
 * <ul>
 *   <li><b>Discord</b> — the whole thing, comment included, as a survey answer
 *       ({@link DiscordService#postSurveyAnswer}), so it lands in the player's thread AND the flat
 *       survey-results channel alongside every other piece of written feedback. Reusing Discord
 *       Presence's existing public seam keeps this entirely in the Dungeon Train repo.</li>
 *   <li><b>The relay</b> — {@code modId} and the requested flag only, as a
 *       {@code /telemetry/mod-rec} event, so the data explorer can count which mods get
 *       recommended. <b>The comment is deliberately absent.</b> The relay's rule (see
 *       {@code analytics.js} and {@link BookReadReporter}) is that game-generated metadata may be
 *       stored verbatim and player free-text may not; a recommendation comment is free-text, so it
 *       stays in the moderated Discord channel and never reaches a stored telemetry row.</li>
 * </ul>
 *
 * <p>Both halves are best-effort and no-throw — a Discord hiccup or a relay outage can never
 * disrupt the packet handler, and the outbox redelivers the telemetry event on the next flush. The
 * caller has already gated on the player's network consent.</p>
 */
public final class ModRecommendReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ModRecommendReporter() {}

    /** Post the recommendation to Discord and queue its text-free telemetry event. */
    public static void report(ServerPlayer player, ModRecommendPacket packet) {
        if (player == null || packet == null) return;
        postToDiscord(player, packet);
        postToRelay(player.getUUID(), packet);
    }

    private static void postToDiscord(ServerPlayer player, ModRecommendPacket packet) {
        try {
            String name = displayName(packet);
            String title = packet.requested()
                    ? "🧩 " + player.getGameProfile().getName() + " asked for a mod"
                    : "🧩 " + player.getGameProfile().getName() + " recommends a mod";
            String description = packet.requested()
                    ? "Not installed — requested from the death screen."
                    : "Running it now — recommended from the death screen.";
            List<DeathField> fields = List.of(
                    new DeathField("Mod", name),
                    new DeathField("Why", packet.comment()));
            DiscordService.get().postSurveyAnswer(player, title, description, fields, List.of());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] mod recommendation to Discord failed: {}", t.toString());
        }
    }

    private static void postToRelay(UUID playerId, ModRecommendPacket packet) {
        try {
            String uuid = playerId.toString().replace("-", "");
            RelayOutbox.get().enqueue("/telemetry/mod-rec", buildPayload(uuid, packet).toString());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] mod recommendation telemetry failed to queue: {}", t.toString());
        }
    }

    /**
     * Pure JSON assembly — package-private so the shape can be unit-tested without a running
     * server. Carries no player free-text: for a recommendation the mod is identified by its
     * {@code modId}; for a request there is no id to send, so only the flag and an empty id
     * travel, and the requested name lives in Discord alone.
     */
    static JsonObject buildPayload(String uuid, ModRecommendPacket packet) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("modId", packet.requested() ? "" : safe(packet.modId()));
        body.addProperty("requested", packet.requested());
        return body;
    }

    /** What to call the mod in Discord: the player's typed name for a request, else the mod's own. */
    private static String displayName(ModRecommendPacket packet) {
        String name = safe(packet.displayName());
        if (!name.isBlank()) return name;
        String id = safe(packet.modId());
        return id.isBlank() ? "(unnamed)" : id;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
