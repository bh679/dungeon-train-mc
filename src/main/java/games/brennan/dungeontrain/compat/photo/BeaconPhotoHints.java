package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.DungeonTrain;
import io.github.mortuusars.exposure.neoforge.api.event.FrameAddedEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.phys.AABB;

import java.util.Optional;

/**
 * Near misses at "Before The Beacon" (a photo of the Wither in the second before it dies), said in
 * chat until it is earned. Too late: the shot caught the Wither red and dying, which Exposure leaves
 * out of the frame. Too early: the Wither was in a photo but died more than a second after it, within
 * {@link #HORIZON_TICKS} (an older photo is no near miss).
 */
final class BeaconPhotoHints {

    static final ResourceLocation ADVANCEMENT =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "enchiridion/before_the_beacon");

    static final String TOO_LATE = "chat.dungeontrain.beacon_hint.too_late";
    static final String TOO_EARLY = "chat.dungeontrain.beacon_hint.too_early";

    /** Ten seconds: how far before the death a shot still reads as "too early". */
    static final long HORIZON_TICKS = 200;

    private BeaconPhotoHints() {}

    /** The whole seconds to quote for a shot this long before the death, or empty when it's no near miss. */
    static Optional<Integer> tooEarlySeconds(long shotTime, long deathTime) {
        long gap = deathTime - shotTime;
        if (gap <= PhotoFinalMoments.WINDOW_TICKS || gap > HORIZON_TICKS) return Optional.empty();
        return Optional.of((int) ((gap + 19) / 20));
    }

    /** A shot that caught a dying Wither in the picture: it came too late. */
    static void onShot(ServerPlayer player, FrameAddedEvent event) {
        CameraView view = CameraView.of(player, event).orElse(null);
        if (view == null) return;
        boolean dyingInFrame = !player.level().getEntitiesOfClass(WitherBoss.class,
                new AABB(player.blockPosition()).inflate(CameraView.SEARCH_RADIUS),
                w -> w.isDeadOrDying() && view.bodyInFrame(w)).isEmpty();
        if (!dyingInFrame || SelfieChallengeHints.isEarned(player, ADVANCEMENT)) return;
        SelfieChallengeHints.tell(player, Component.translatable(TOO_LATE));
    }

    /** The Wither died after the photographer's last shot of it, outside the one-second window. */
    static void onWitherDeath(ServerPlayer photographer, long shotTime, long deathTime) {
        Optional<Integer> seconds = tooEarlySeconds(shotTime, deathTime);
        if (seconds.isEmpty() || SelfieChallengeHints.isEarned(photographer, ADVANCEMENT)) return;
        SelfieChallengeHints.tell(photographer, Component.translatable(TOO_EARLY, seconds.get()));
    }
}
