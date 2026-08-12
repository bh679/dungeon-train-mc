package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderCinematic;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.client.CinematicCameraController;
import games.brennan.dungeontrain.client.snapshot.RideSnapshotCapture;
import games.brennan.dungeontrain.net.BuilderPhotoPacket;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Takes the photo the server asked for and writes it beside the template.
 *
 * <p>Capture has to be client-side — it works by rendering the world an extra time inside a frame
 * and reading back the main framebuffer (see {@code RideSnapshotCapture} for why an off-screen
 * target renders black under Sodium/Veil). Writing is client-side too, which is only sound because a
 * builder world is single-player: the same JVM, the same game directory, so the store's path
 * helpers resolve identically on both sides.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderPhotoClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How long to let the world redraw before taking the picture.
     *
     * <p>Every photo request follows a stamp that has just replaced the carriage volume. The server
     * sends this packet in the same tick it finished stamping, so the client still has the
     * <em>previous</em> template on screen: the block updates are in flight, and the sections have to
     * be rebuilt after they land. Firing on the next frame photographed the outgoing template and
     * filed it under the incoming one's name — every template opened without a photo got a picture of
     * whatever it replaced.</p>
     *
     * <p>A second is far longer than a handful of sections need on an integrated server, and none of
     * it is visible — the capture is an extra off-screen pass — so the margin is free.</p>
     */
    private static final long MESH_SETTLE_MILLIS = 1000L;

    private BuilderPhotoClient() {}

    public static void capture(BuilderPhotoPacket packet) {
        Optional<BuilderPhotoPaths.Kind> kind = BuilderPhotoPaths.Kind.fromId(packet.kindId());
        if (kind.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Builder photo: unknown kind '{}'", packet.kindId());
            return;
        }
        CarriagePartKind partKind = CarriagePartKind.fromId(packet.partKindId());
        TrackKind trackKind = TrackKind.fromId(packet.trackKindId());
        Optional<Path> target =
                BuilderPhotoPaths.photoFor(kind.get(), packet.id(), partKind, trackKind);
        if (target.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Builder photo: no path for {} '{}'", packet.kindId(), packet.id());
            return;
        }
        if (packet.onlyIfMissing() && Files.isRegularFile(target.get())) {
            return;   // backfill only — this template already has its picture
        }
        Optional<Path> source =
                BuilderPhotoPaths.sourcePhotoFor(kind.get(), packet.id(), partKind, trackKind);

        Vec3 cam = new Vec3(packet.camX(), packet.camY(), packet.camZ());
        Vec3 focus = new Vec3(packet.focusX(), packet.focusY(), packet.focusZ());
        float[] aim = BuilderCinematic.faceYawPitch(cam, focus);
        CinematicCameraController.Pose pose =
                new CinematicCameraController.Pose(cam, aim[0], aim[1]);

        RideSnapshotCapture.requestPoseCapture(pose, MESH_SETTLE_MILLIS, image -> {
            write(image, target.get(), source);
            // The New screen may already have cached "this template has no photo"; without this the
            // thing you just saved keeps showing the fallback until the world is reloaded.
            BuilderPhotoTextures.invalidate(kind.get(), packet.id(), partKind, trackKind);
        });
    }

    /** The capture hands over ownership of {@code image}, so this closes it however it ends. */
    private static void write(NativeImage image, Path target, Optional<Path> source) {
        try (NativeImage owned = image) {
            Files.createDirectories(target.getParent());
            // Via a sibling temp + atomic move: a crash mid-write must not leave a truncated PNG
            // sitting next to a perfectly good .nbt, which would read as a corrupt template.
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            owned.writeToFile(tmp);
            move(tmp, target);
            LOGGER.info("[DungeonTrain] Builder photo written: {}", target);

            if (source.isPresent()) {
                // Dev mode: the template itself is promoted into the source tree so authored content
                // ships in the next build. A photo that stayed behind would describe data that moved.
                Files.createDirectories(source.get().getParent());
                Files.copy(target, source.get(), StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[DungeonTrain] Builder photo promoted to source: {}", source.get());
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Builder photo write failed for {}: {}", target, e.toString());
        }
    }

    private static void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
