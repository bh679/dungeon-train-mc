package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;

/**
 * Shows a tributed photo to the whole community: when a player pays Tribute to a found photo
 * ({@code SharedPhotos.payTribute}), the picture itself is posted top-level to the public passenger
 * log — the same feed as the death manifest and remote-echo stories
 * ({@link DungeonTrain#manifestWebhookOverride()}) — naming who paid and whose photo it is.
 *
 * <p>A Tribute is one player saying "this one is worth keeping", which is exactly the photo worth
 * showing everyone. No @-mention: tributes are an everyday event, not a milestone. Best-effort,
 * like every other reporter here — Discord can never disturb the Tribute.</p>
 */
public final class TributePhotoReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String PHOTO_FILENAME = "photo.png";
    /** Emerald green — the Tribute's own colour (it is paid in emeralds and burns green). */
    static final int EMBED_COLOR = 0x2ECC71;

    private TributePhotoReporter() {}

    /**
     * Post the tributed photo. Server thread.
     *
     * @param photographer who took the photo, as the found photo names them ({@code ""} if unknown)
     * @param tributeNumber which Tribute this is for the photo, counting this one (1 = its first)
     * @param cost emeralds this Tribute cost
     * @param png the photo, encoded
     */
    public static void post(ServerPlayer tributer, String photographer, int tributeNumber, int cost, byte[] png) {
        if (png == null || png.length == 0) return;
        try {
            String name = tributer.getGameProfile().getName();
            LOGGER.info("[DungeonTrain] {} paid tribute to a photo by {} — posting it to the passenger log.",
                    name, photographer == null || photographer.isBlank() ? "an unknown passenger" : photographer);
            DiscordService.get().postReportTopLevel(tributer, title(name, photographer),
                    description(tributeNumber, cost), List.of(), png, PHOTO_FILENAME, EMBED_COLOR,
                    DungeonTrain.manifestWebhookOverride());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] tributed photo post failed: {}", t.toString());
        }
    }

    /** {@code "📸 Steve paid tribute to a photo by Alex"}; a photo with no known photographer says so plainly. */
    static String title(String tributer, String photographer) {
        boolean known = photographer != null && !photographer.isBlank();
        boolean own = known && photographer.equals(tributer);
        if (own) return "📸 " + tributer + " paid tribute to their own photo";
        return "📸 " + tributer + " paid tribute to a photo by " + (known ? photographer : "a fellow passenger");
    }

    /** {@code "Tribute #3 · 3 emeralds"} — how many times this photo has been kept alive, and what it cost. */
    static String description(int tributeNumber, int cost) {
        return "Tribute #" + Math.max(1, tributeNumber) + " · " + cost + (cost == 1 ? " emerald" : " emeralds");
    }
}
