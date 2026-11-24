package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Server side of the report flag on a found photo ({@code client/PhotoReportOverlay}). The photo is
 * read from the player's hand, never taken from the client, and the report goes to the relay's
 * {@code /photos/report} through the durable {@link RelayOutbox}. The relay keeps one report per
 * (photo, player) and holds the photo for review once enough of its viewers report it.
 *
 * <p>The photo itself is not touched here: the client closes the view right after, and the normal
 * close path counts the view and burns it ({@link SharedPhotos#reportView}).</p>
 */
public final class PhotoReports {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PhotoReports() {}

    public static void report(ServerPlayer player, String reason) {
        if (!PhotoReportReason.isValid(reason)) return;
        int photoId = SharedPhotos.sharedId(player.getMainHandItem());
        if (photoId <= 0) photoId = SharedPhotos.sharedId(player.getOffhandItem());
        if (photoId <= 0) return; // not holding a found photo any more
        if (!NetworkConsentMirror.isGranted(player)) return;
        try {
            String uuid = player.getUUID().toString().replace("-", "");
            RelayOutbox.get().enqueue(SharedPhotos.REPORT_PATH,
                buildPayload(uuid, photoId, reason, UUID.randomUUID().toString()).toString());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Photo report for {} could not be queued: {}", photoId, t.toString());
        }
    }

    /** Package-private so the body shape is unit-testable. */
    static JsonObject buildPayload(String uuid, int photoId, String reason, String key) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("photoId", photoId);
        body.addProperty("reason", reason);
        body.addProperty("key", key);
        return body;
    }
}
