package games.brennan.dungeontrain.echo;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.CaptureEchoPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

/**
 * Server-side trigger that asks {@code player}'s client to capture a framed screenshot of a remote
 * echo at first eye-contact, for the encounter story's embed image.
 *
 * <p>Sends a clientbound {@link CaptureEchoPacket}; the client frames the snapshot camera on the echo
 * and uploads the PNG back via {@code EchoPhotoPacket} → {@link RemoteEchoEncounters#onPhoto}. Gated on
 * the same {@code echoEncounterToDiscord} flag as the eventual post, so a disabled feature never asks
 * for a screenshot. Best-effort: any failure is swallowed so it can never break the encounter scan.</p>
 */
final class EchoSnapshotRequests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EchoSnapshotRequests() {}

    /** Request a one-shot framed capture of {@code echo} from {@code player}'s client. */
    static void requestIfEnabled(ServerPlayer player, Entity echo) {
        if (!DungeonTrainConfig.isEchoEncounterToDiscord()) return; // same gate as the post
        try {
            DungeonTrainNet.sendTo(player, new CaptureEchoPacket(echo.getId()));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] echo snapshot request failed: {}", t.toString());
        }
    }
}
