package games.brennan.dungeontrain.client.snapshot;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.AdvancementPhotoPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The photo a milestone announcement carries: the game's framed third-person "photo moment" of the
 * player ({@link RideSnapshotCapture#requestEchoCapture} around the player themself — the same lit,
 * unobstructed framing the ride gallery and echo stories use), with the advancement's
 * "Challenge Complete!" toast painted on afterwards ({@link ToastOverlayPainter}), since the snapshot
 * pass draws the level only. Asked for by {@code CaptureAdvancementPacket}; the JPEG goes back as
 * {@link AdvancementPhotoPacket}.
 *
 * <p>If no clean angle is found within the capture's bounded retries the request is dropped
 * silently by the capture; the server's buffer then posts text-only after its own timeout.</p>
 */
public final class AdvancementToastCapture {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Stays under {@link AdvancementPhotoPacket}'s 1 MB codec cap, like the death-screen ride photo. */
    private static final int MAX_BYTES = 1_000_000;
    /** Set {@code -Ddungeontrain.milestonePhotoDump=true} to also write each photo to {@code screenshots/}. */
    private static final boolean DUMP = Boolean.getBoolean("dungeontrain.milestonePhotoDump");

    private AdvancementToastCapture() {}

    /** Queue the framed capture for {@code advancementId}; a newer request replaces an unsent one. */
    public static void request(ResourceLocation advancementId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) return;
        AdvancementHolder holder = mc.getConnection().getAdvancements().get(advancementId);
        DisplayInfo display = holder == null ? null : holder.value().display().orElse(null);
        RideSnapshotCapture.requestEchoCapture(mc.player.getId(),
                bytes -> mc.execute(() -> finish(mc, advancementId, display, bytes)));
    }

    /** Render thread: paint the toast on the framed shot, encode, send. {@code bytes} is the capture's JPEG. */
    private static void finish(Minecraft mc, ResourceLocation advancementId, DisplayInfo display, byte[] bytes) {
        byte[] jpeg = null;
        if (bytes != null && bytes.length > 0) {
            try (NativeImage photo = NativeImage.read(new ByteArrayInputStream(bytes))) {
                if (display == null) {
                    jpeg = SnapshotJpegEncoder.encode(photo, MAX_BYTES);
                } else {
                    try (NativeImage framed = ToastOverlayPainter.paint(mc, photo, display)) {
                        jpeg = SnapshotJpegEncoder.encode(framed, MAX_BYTES);
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] milestone photo for {} failed: {}", advancementId, t.toString());
            }
        }
        if (DUMP && jpeg != null) dump(mc, advancementId, jpeg);
        // An empty image still goes back so the server posts promptly instead of waiting out its timeout.
        DungeonTrainNet.sendToServer(new AdvancementPhotoPacket(advancementId, jpeg != null ? jpeg : new byte[0]));
    }

    private static void dump(Minecraft mc, ResourceLocation advancementId, byte[] jpeg) {
        try {
            Path dir = mc.gameDirectory.toPath().resolve("screenshots");
            Files.createDirectories(dir);
            Path file = dir.resolve("milestone-" + advancementId.getPath().replace('/', '_') + ".jpg");
            Files.write(file, jpeg);
            LOGGER.info("[DungeonTrain] milestone photo written to {}", file);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] milestone photo dump failed: {}", e.toString());
        }
    }
}
