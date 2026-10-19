package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.DungeonTrain;
import io.github.mortuusars.exposure.neoforge.api.event.FrameAddedEvent;
import io.github.mortuusars.exposure.util.Fov;
import io.github.mortuusars.exposure.util.PointOfView;
import io.github.mortuusars.exposure.world.camera.frame.EntitiesInFrame;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * Nudges toward the hidden "Selfie with an Unstoppable Force And Immovable Object" challenge, which
 * wants a Warden and the Wither in one selfie. A photo of both that isn't a selfie says so; a selfie
 * where a Warden or Wither was in the picture but didn't count says why: too far away, or its eyes
 * out of frame. Silent once the challenge is earned.
 *
 * <p>The reasons mirror Exposure's own test ({@code EntitiesInFrame.get}): a mob counts when its eyes
 * are inside the camera's view, it's near enough for its size, and nothing solid is in the way. The
 * camera's point of view and field of view are recomputed from the camera the same way it took the
 * shot ({@code CameraItem#getPointOfView}, {@code #getViewfinderFov}).</p>
 */
public final class SelfieChallengeHints {

    static final ResourceLocation ADVANCEMENT =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "enchiridion/unstoppable_force");

    static final String NOT_SELFIE = "chat.dungeontrain.selfie_hint.not_selfie";
    private static final String KEY_PREFIX = "chat.dungeontrain.selfie_hint.";

    /** Exposure narrows the viewfinder's field of view by this much for its in-frame test. */
    private static final double FOV_SCALE = 0.95;
    /** Exposure's search radius around the camera holder. */
    private static final double SEARCH_RADIUS = 128.0;

    /** Why a mob in the picture didn't count. */
    enum Diagnosis { NONE, TOO_FAR, EYES }

    private SelfieChallengeHints() {}

    /** The not-a-selfie hint: both bosses counted, but the camera faced away from the photographer. */
    static Optional<String> hintKey(boolean selfie, boolean warden, boolean wither) {
        return !selfie && warden && wither ? Optional.of(NOT_SELFIE) : Optional.empty();
    }

    /** The reason, in Exposure's own order: in view at all, then distance, then the eyes. */
    static Diagnosis diagnose(boolean bodyInFrame, boolean tooFar, boolean eyesInFrame) {
        if (!bodyInFrame) return Diagnosis.NONE;
        if (tooFar) return Diagnosis.TOO_FAR;
        if (!eyesInFrame) return Diagnosis.EYES;
        return Diagnosis.NONE;
    }

    /** {@code warden}/{@code wither} + the diagnosis, or empty when there is nothing to say. */
    static Optional<String> diagnosisKey(String mob, Diagnosis diagnosis) {
        return switch (diagnosis) {
            case TOO_FAR -> Optional.of(KEY_PREFIX + mob + "_too_far");
            case EYES -> Optional.of(KEY_PREFIX + mob + "_eyes");
            case NONE -> Optional.empty();
        };
    }

    /** Tell the photographer what the shot is missing, until they've earned the challenge. */
    static void send(ServerPlayer player, FrameAddedEvent event, boolean selfie) {
        List<LivingEntity> inFrame = event.getEntitiesInFrame();
        boolean warden = false, wither = false;
        for (LivingEntity e : inFrame) {
            if (e.getType() == EntityType.WARDEN) warden = true;
            if (e.getType() == EntityType.WITHER) wither = true;
        }
        Optional<String> notSelfie = hintKey(selfie, warden, wither);
        if (notSelfie.isEmpty() && !selfie) return;
        if (isEarned(player)) return;
        if (notSelfie.isPresent()) {
            tell(player, Component.translatable(notSelfie.get(), Component.keybind("key.togglePerspective")));
            return;
        }
        if (!(event.getCamera().getItem() instanceof CameraItem camera)) return;
        View view = View.of(player, camera, event);
        for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class,
                new AABB(player.blockPosition()).inflate(SEARCH_RADIUS), LivingEntity::isAlive)) {
            String mob = e.getType() == EntityType.WARDEN ? "warden" : e.getType() == EntityType.WITHER ? "wither" : null;
            if (mob == null || inFrame.contains(e)) continue;
            diagnosisKey(mob, view.diagnose(e)).ifPresent(key -> tell(player, Component.translatable(key)));
        }
    }

    /** The camera's view at the moment of the shot, as Exposure saw it. */
    private record View(Vec3 pos, EntitiesInFrame.FrustumCheck frustum, double focalLength) {

        static View of(ServerPlayer player, CameraItem camera, FrameAddedEvent event) {
            ItemStack stack = event.getCamera();
            PointOfView pov = camera.getPointOfView(event.getCameraHolder(), stack);
            double fov = camera.getViewfinderFov(player.level(), stack) * FOV_SCALE;
            return new View(pov.pos(),
                    EntitiesInFrame.FrustumCheck.createFromCamera(pov.pos(), pov.dir(), (float) Math.toRadians(fov)),
                    Fov.fovToFocalLength(fov));
        }

        Diagnosis diagnose(LivingEntity e) {
            return SelfieChallengeHints.diagnose(bodyInFrame(e.getBoundingBox()),
                    EntitiesInFrame.calculateVisibleDistance(pos, e) > focalLength,
                    frustum.contains(e.getEyePosition()));
        }

        /** Any corner or the centre of the mob's box inside the view: some of it is in the picture. */
        private boolean bodyInFrame(AABB box) {
            if (frustum.contains(box.getCenter())) return true;
            for (double x : new double[] {box.minX, box.maxX})
                for (double y : new double[] {box.minY, box.maxY})
                    for (double z : new double[] {box.minZ, box.maxZ})
                        if (frustum.contains(new Vec3(x, y, z))) return true;
            return false;
        }
    }

    private static void tell(ServerPlayer player, Component line) {
        player.sendSystemMessage(line.copy().withStyle(ChatFormatting.GRAY));
    }

    private static boolean isEarned(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return true;
        AdvancementHolder holder = server.getAdvancements().get(ADVANCEMENT);
        return holder == null || player.getAdvancements().getOrStartProgress(holder).isDone();
    }
}
