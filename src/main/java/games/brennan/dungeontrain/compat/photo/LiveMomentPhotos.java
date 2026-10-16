package games.brennan.dungeontrain.compat.photo;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.LiveMomentEvents;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.MomentKind;
import io.github.mortuusars.exposure.ExposureServer;
import io.github.mortuusars.exposure.world.photograph.PhotographType;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.level.storage.ExposureData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A photo the Live Eye streamer takes, shared into the stream's Live chat ({@link LiveMomentEvents}).
 * The shot's pixels reach the server a moment after the frame does, so the photo waits for them here,
 * then goes out on its photograph paper, framed as the tribute-photos channel shows a photo.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveMomentPhotos {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** How long to wait for the pixels (5 s), as {@code SharedPhotos} does. */
    private static final int PIXEL_WAIT_TICKS = 100;

    private record Pending(UUID player, String exposureId, int ticksLeft) {
        Pending tick() {
            return new Pending(player, exposureId, ticksLeft - 1);
        }
    }

    private static List<Pending> pending = List.of();

    private LiveMomentPhotos() {}

    /** A frame was added to {@code player}'s camera: queue it if they are the live streamer. */
    static void onFrameAdded(ServerPlayer player, Frame frame) {
        // Projected frames are images from outside the game — never shared.
        if (frame.isProjected() || !frame.identifier().isId()) return;
        if (!LiveMomentEvents.isLiveStreamer(player)) return;
        List<Pending> next = new ArrayList<>(pending);
        next.add(new Pending(player.getUUID(), frame.identifier().id(), PIXEL_WAIT_TICKS));
        pending = List.copyOf(next);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (pending.isEmpty()) return;
        MinecraftServer server = event.getServer();
        List<Pending> waiting = new ArrayList<>();
        for (Pending p : pending) {
            Optional<ExposureData> data = ExposureServer.exposureRepository().load(p.exposureId()).getData();
            if (data.isPresent()) {
                // On the paper a fresh print wears, like the tribute-photos channel's posts (OwnPhotoTribute).
                SharedPhotos.encodeForDiscord(server, data.get(), PhotoPaperTextures.paper(PhotographType.REGULAR), p.exposureId(), png -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(p.player());
                    if (player != null) LiveMomentEvents.share(player, MomentKind.PHOTO, "", "", png);
                });
            } else if (p.ticksLeft() > 1) {
                waiting.add(p.tick());
            } else {
                LOGGER.debug("[DungeonTrain] Live moment photo {} never reached the server; not shared.", p.exposureId());
            }
        }
        pending = List.copyOf(waiting);
    }
}
