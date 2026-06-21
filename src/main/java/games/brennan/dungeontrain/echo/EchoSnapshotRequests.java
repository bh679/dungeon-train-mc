package games.brennan.dungeontrain.echo;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Server-side trigger that asks {@code player}'s client to capture a framed screenshot of a remote
 * echo at first eye-contact, for the encounter story's embed image.
 *
 * <p>Stage B ships text-only stories, so this is a no-op placeholder; Stage C fills
 * {@link #requestIfEnabled} with a clientbound {@code CaptureEchoPacket} send (the client frames the
 * snapshot camera on the echo and uploads the PNG back via {@code EchoPhotoPacket} →
 * {@link RemoteEchoEncounters#onPhoto}).</p>
 */
final class EchoSnapshotRequests {

    private EchoSnapshotRequests() {}

    /** Request a one-shot framed capture of {@code echo} from {@code player}'s client. No-op until Stage C. */
    static void requestIfEnabled(ServerPlayer player, Entity echo) {
        // Stage C: send CaptureEchoPacket(echo.getId()) to player.
    }
}
