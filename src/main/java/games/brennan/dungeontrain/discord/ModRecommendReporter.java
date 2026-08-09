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
 *   <li><b>The relay</b> — the whole thing too, as a {@code /telemetry/mod-rec} post, so the data
 *       explorer's Mods page can rank what gets recommended <em>and</em> show what players said
 *       about it. The relay splits it again on arrival: {@code modId} and the requested flag become
 *       the text-free {@code mod_rec} analytics event (the stream still holds no player prose, the
 *       rule {@code analytics.js} and {@link BookReadReporter} state), while {@code name} and
 *       {@code comment} go to a separate capped, admin-only table ({@code modrecstore.js}).</li>
 * </ul>
 *
 * <p>Sending the text is a deliberate change (2026-08-08, relay v0.128.0), and it is what makes a
 * <b>request</b> legible at all: a mod the player does not have installed has no {@code modId}, so
 * the name they typed is its only identity — previously that name existed nowhere but Discord, where
 * nothing could count or query it. An older relay simply ignores the two extra fields.</p>
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
     * server.
     *
     * <p>{@code modId} identifies a recommendation and is empty for a request (nothing on the client
     * can resolve an id for a mod that isn't installed). {@code name} is the display name — the
     * mod's own, or for a request the one the player typed — and {@code comment} is why. The relay
     * keeps the last two out of the analytics event and in their own table; see the class doc.</p>
     */
    static JsonObject buildPayload(String uuid, ModRecommendPacket packet) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("modId", packet.requested() ? "" : safe(packet.modId()));
        body.addProperty("requested", packet.requested());
        body.addProperty("name", safe(packet.displayName()).isBlank()
                // Not displayName()'s "(unnamed)" placeholder: that is a Discord label, and storing
                // it would put a fake name in a data row the explorer reads as the mod's own.
                ? safe(packet.modId())
                : safe(packet.displayName()));
        body.addProperty("comment", safe(packet.comment()));
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
