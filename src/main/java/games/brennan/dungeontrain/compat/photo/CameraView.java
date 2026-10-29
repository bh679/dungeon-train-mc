package games.brennan.dungeontrain.compat.photo;

import io.github.mortuusars.exposure.neoforge.api.event.FrameAddedEvent;
import io.github.mortuusars.exposure.util.Fov;
import io.github.mortuusars.exposure.util.PointOfView;
import io.github.mortuusars.exposure.world.camera.frame.EntitiesInFrame;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * The camera's view at the moment of a shot, as Exposure saw it. Mirrors Exposure's own test
 * ({@code EntitiesInFrame.get}): a mob counts when its eyes are inside the camera's view, it's near
 * enough for its size, and nothing solid is in the way. The point of view and field of view are
 * recomputed from the camera the same way it took the shot ({@code CameraItem#getPointOfView},
 * {@code #getViewfinderFov}).
 */
record CameraView(Vec3 pos, EntitiesInFrame.FrustumCheck frustum, double focalLength) {

    /** Exposure narrows the viewfinder's field of view by this much for its in-frame test. */
    private static final double FOV_SCALE = 0.95;
    /** Exposure's search radius around the camera holder. */
    static final double SEARCH_RADIUS = 128.0;

    /** The shot's view, or empty when the shot wasn't taken with a camera item. */
    static Optional<CameraView> of(ServerPlayer player, FrameAddedEvent event) {
        if (!(event.getCamera().getItem() instanceof CameraItem camera)) return Optional.empty();
        ItemStack stack = event.getCamera();
        PointOfView pov = camera.getPointOfView(event.getCameraHolder(), stack);
        double fov = camera.getViewfinderFov(player.level(), stack) * FOV_SCALE;
        return Optional.of(new CameraView(pov.pos(),
                EntitiesInFrame.FrustumCheck.createFromCamera(pov.pos(), pov.dir(), (float) Math.toRadians(fov)),
                Fov.fovToFocalLength(fov)));
    }

    /** Too far away for Exposure to count it, for its size. */
    boolean tooFar(LivingEntity e) {
        return EntitiesInFrame.calculateVisibleDistance(pos, e) > focalLength;
    }

    boolean eyesInFrame(LivingEntity e) {
        return frustum.contains(e.getEyePosition());
    }

    /** Any corner or the centre of the mob's box inside the view: some of it is in the picture. */
    boolean bodyInFrame(LivingEntity e) {
        AABB box = e.getBoundingBox();
        if (frustum.contains(box.getCenter())) return true;
        for (double x : new double[] {box.minX, box.maxX})
            for (double y : new double[] {box.minY, box.maxY})
                for (double z : new double[] {box.minZ, box.maxZ})
                    if (frustum.contains(new Vec3(x, y, z))) return true;
        return false;
    }
}
