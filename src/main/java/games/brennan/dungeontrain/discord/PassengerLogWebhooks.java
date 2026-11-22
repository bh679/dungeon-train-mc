package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DeathField;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.event.ContentModeMirror;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * A player's own passenger log: their death manifest, milestones, photos PlayerMobs take of them and their
 * echo encounter stories also go to each of their own Discord webhooks that gets {@link WebhookFeed#PASSENGER_LOG}.
 * Sent by the Minecraft server, like the Tribute copies ({@link TributePhotoReporter#postPersonal}).
 *
 * <p>It is the player's own channel, so the public feed's rules (short runs, Free Play, Public posts) do not
 * apply. Test mode, Kid mode and a player with Dungeon Train's internet connection off send nothing. Never
 * pings anyone ({@link TributeWebhook} posts with {@code allowed_mentions.parse = []}). Best-effort: a post
 * that fails is dropped, and nothing here can disturb the public post or the game.</p>
 */
public final class PassengerLogWebhooks {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PassengerLogWebhooks() {}

    /**
     * {@code player}'s passenger log links right now, or none when they may not get any. A snapshot: a post
     * buffered for a photo keeps these even if the player has logged out by the time it goes.
     */
    public static List<String> links(ServerPlayer player) {
        try {
            if (player == null || RunIntegrity.isOffRecordWorld()) return List.of();
            if (ContentModeMirror.isKid(player) || !NetworkConsentMirror.isGranted(player)) return List.of();
            return TributeWebhookMirror.get(player.getUUID(), WebhookFeed.PASSENGER_LOG);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] passenger log webhook lookup failed: {}", t.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Post to {@code player}'s passenger log links, if any. */
    public static void post(ServerPlayer player, String title, String description, List<DeathField> fields,
                            byte[] image, String filename, int color) {
        if (player == null) return;
        post(links(player), player.getGameProfile().getName(), player.getUUID(), title, description, fields,
                image, filename, color);
    }

    /**
     * Post to {@code links} (from {@link #links}). Async; never throws.
     *
     * @param image the photo, or null/empty for a text-only post
     * @param filename the photo's file name ({@code .jpg} or {@code .png})
     */
    public static void post(List<String> links, String username, UUID uuid, String title, String description,
                            List<DeathField> fields, byte[] image, String filename, int color) {
        if (links == null || links.isEmpty()) return;
        try {
            boolean hasImage = image != null && image.length > 0 && filename != null;
            List<TributeWebhook.Field> embedFields = fields == null ? List.of()
                    : fields.stream().map(f -> new TributeWebhook.Field(f.name(), f.value())).toList();
            for (String url : links) {
                TributeWebhook.post(url, username, uuid,
                        TributeWebhook.embed(title, description, color, embedFields, hasImage ? filename : null),
                        hasImage ? image : null, filename);
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] passenger log webhook post failed: {}", t.getClass().getSimpleName());
        }
    }
}
