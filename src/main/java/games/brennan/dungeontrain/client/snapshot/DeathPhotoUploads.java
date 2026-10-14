package games.brennan.dungeontrain.client.snapshot;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.DeathStatsCache;
import games.brennan.dungeontrain.net.DeathPhotoPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.RideGalleryPacket;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Sends a death's ride photos to the server without stalling the frame the player dies on.
 *
 * <p>Both uploads used to JPEG-encode on the client thread inside the death screen's
 * {@code init()} — a disk decode, a per-pixel copy and a quality ladder per photo — which landed
 * as a visible hitch on the very frame of death, reading to players as "the lag killed me". Here
 * the encodes run on the background executor and the packet goes out from the client thread once
 * they finish.</p>
 *
 * <p>Each upload happens at most once per death. A death is identified by its
 * {@link DeathStatsCache} packet instance (a fresh object every death), so the death-moment screen
 * can send the fall photo early and the narrative screen's later call is a no-op.</p>
 */
public final class DeathPhotoUploads {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Under {@link DeathPhotoPacket}'s 1 MB codec cap — a hi-res shot is shrunk to fit. */
    private static final int FALL_PHOTO_MAX_BYTES = 1_000_000;
    /** Under {@link RideGalleryPacket}'s 2 MB per-photo codec cap. */
    private static final int GALLERY_PHOTO_MAX_BYTES = 1_950_000;

    private static Object fallSentFor;
    private static Object gallerySentFor;

    private DeathPhotoUploads() {}

    /**
     * Hand this death's fall-page photo to the server for the Discord death report. Sends an empty
     * array when there is no photo, so the server posts promptly with its gear-composite fallback.
     */
    public static void sendFallPhoto(RideSnapshot fall) {
        Object death = currentDeath();
        if (death != null && death == fallSentFor) return;
        fallSentFor = death;
        CompletableFuture<byte[]> jpeg = fall != null
                ? fall.photoBytesAsync(FALL_PHOTO_MAX_BYTES, worker())
                : CompletableFuture.completedFuture(null);
        jpeg.whenComplete((bytes, err) -> sendOnClientThread(
                new DeathPhotoPacket(bytes != null ? bytes : new byte[0]), err));
    }

    /**
     * Upload every distinct photo shown across the death pages (duplicates reused across pages are
     * skipped) to the relay's Photos page. Sends nothing when no photo encodes.
     */
    public static void sendGallery(RideSnapshot[] pageBackgrounds) {
        Object death = currentDeath();
        if (death != null && death == gallerySentFor) return;
        gallerySentFor = death;
        Set<RideSnapshot> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<RideSnapshot> shots = new ArrayList<>();
        List<CompletableFuture<byte[]>> jobs = new ArrayList<>();
        for (RideSnapshot s : pageBackgrounds) {
            if (s == null || !seen.add(s)) continue;
            shots.add(s);
            jobs.add(s.photoBytesAsync(GALLERY_PHOTO_MAX_BYTES, worker()));
        }
        if (jobs.isEmpty()) return;
        CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).whenComplete((v, err) -> {
            List<RideGalleryPacket.Photo> photos = toPhotos(shots, jobs);
            if (!photos.isEmpty()) sendOnClientThread(new RideGalleryPacket(photos), err);
        });
    }

    private static List<RideGalleryPacket.Photo> toPhotos(List<RideSnapshot> shots,
                                                          List<CompletableFuture<byte[]>> jobs) {
        List<RideGalleryPacket.Photo> photos = new ArrayList<>(shots.size());
        for (int i = 0; i < shots.size(); i++) {
            byte[] jpeg = jobs.get(i).getNow(null);
            if (jpeg == null || jpeg.length == 0) continue;
            RideSnapshot s = shots.get(i);
            SnapshotMeta m = s.meta();
            String tag = s.tag() != null ? s.tag().name() : "";
            photos.add(new RideGalleryPacket.Photo(tag, m.biome(), m.band(), m.difficulty(), m.cart(),
                    m.gfx(), m.shaderpack(), s.photoId(), jpeg));
        }
        return photos;
    }

    private static void sendOnClientThread(CustomPacketPayload payload, Throwable err) {
        if (err != null) LOGGER.warn("[DungeonTrain] Death photo encode failed: {}", err.toString());
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            // The player may have left the world while the encode ran — nothing to send to then.
            if (mc.getConnection() == null) return;
            DungeonTrainNet.sendToServer(payload);
        });
    }

    private static Object currentDeath() {
        return DeathStatsCache.get();
    }

    private static Executor worker() {
        return Util.backgroundExecutor();
    }
}
