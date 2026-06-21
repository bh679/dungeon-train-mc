package games.brennan.dungeontrain.client.snapshot;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EchoPhotoPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Client-side orchestrator for the remote-echo encounter screenshot (Stage C). Invoked by the
 * {@code CaptureEchoPacket} handler when the server reports first eye-contact with a remote echo.
 *
 * <p>Resolves the echo entity locally, then queues a one-shot framed capture of it
 * ({@link RideSnapshotCapture#requestEchoCapture}); the resulting PNG is uploaded back to the server
 * via {@link EchoPhotoPacket}, keyed by the echo's UUID so the server can match it to the right
 * journal. Kept out of the {@code net} package so the client-only imports never load server-side.</p>
 */
public final class EchoSnapshotClient {

    private EchoSnapshotClient() {}

    /**
     * Frame and capture the echo {@code echoEntityId}, then send its PNG to the server. No-op if the
     * entity isn't loaded/alive on this client (the encounter then posts text-only).
     */
    public static void capture(int echoEntityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity echo = mc.level.getEntity(echoEntityId);
        if (echo == null || !echo.isAlive()) return;

        UUID echoId = echo.getUUID();
        RideSnapshotCapture.requestEchoCapture(echoEntityId, png -> {
            if (png != null && png.length > 0) {
                DungeonTrainNet.sendToServer(new EchoPhotoPacket(echoId, png));
            }
        });
    }
}
