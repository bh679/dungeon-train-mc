package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;

/**
 * Shows the community a photo a PlayerMob took of a passenger: when a player gifts a PlayerMob a
 * camera and it photographs them ({@code compat.PlayerMobPhotoGoal}), the picture is posted top-level
 * to the public passenger log — the same feed as the death manifest and tributed photos
 * ({@link DungeonTrain#manifestWebhookOverride()}) — credited to the mob by name.
 *
 * <p>Posted in the photographed player's name (the webhook identity), with the mob as the
 * photographer in the title. No @-mention: an everyday moment, not a milestone. Best-effort — Discord
 * can never disturb the photo.</p>
 */
public final class PlayerMobPhotoReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String PHOTO_FILENAME = "photo.png";
    /** Polaroid cream — the print's own colour. */
    static final int EMBED_COLOR = 0xF5E6C8;

    private PlayerMobPhotoReporter() {}

    /**
     * Post the mob's photo. Server thread.
     *
     * @param subject the player in the photo — the one who gifted the camera
     * @param photographer the PlayerMob's display name
     * @param png the photo, encoded for Discord
     */
    public static void post(ServerPlayer subject, String photographer, byte[] png) {
        if (png == null || png.length == 0) return;
        try {
            String name = subject.getGameProfile().getName();
            LOGGER.info("[DungeonTrain] {} photographed {} — posting it to the passenger log.", photographer, name);
            DiscordService.get().postReportTopLevel(subject, title(photographer, name), description(photographer, name),
                    List.of(), png, PHOTO_FILENAME, EMBED_COLOR, DungeonTrain.manifestWebhookOverride());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] PlayerMob photo post failed: {}", t.toString());
        }
    }

    /** {@code "📸 Shutterbug took a photo of Steve"}; a nameless mob is "a fellow passenger". */
    static String title(String photographer, String subject) {
        String by = photographer == null || photographer.isBlank() ? "A fellow passenger" : photographer;
        return "📸 " + by + " took a photo of " + subject;
    }

    /** {@code "Steve handed over a camera · Shutterbug handed back the print"}. */
    static String description(String photographer, String subject) {
        String by = photographer == null || photographer.isBlank() ? "They" : photographer;
        return subject + " handed over a camera · " + by + " handed back the print";
    }
}
