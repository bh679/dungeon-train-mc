package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.snapshot.CarriageOcclusion;
import games.brennan.dungeontrain.client.snapshot.SnapshotCamera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * The one rule every death scene shot obeys: the lens never starts inside a block and never moves
 * through one. A shot's lens path is sampled, each sample must sit in open air (no world block
 * with a collision shape, no carriage block), and the subject must be in view from the first,
 * middle and last sample. Shots that fail are skipped; train-locked shots are re-tested while they
 * play, since the train can run into a tunnel mid-shot.
 */
public final class ShotPath {

    /** Samples along a moving shot's path. */
    static final int SAMPLES = 9;

    private ShotPath() {}

    /** Pure: {@code SAMPLES} points along {@code f -> point(f)} for f in [0, 1]. */
    static List<Vector3d> samples(DoubleFunction<Vector3d> point) {
        List<Vector3d> out = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) out.add(point.apply(i / (double) (SAMPLES - 1)));
        return out;
    }

    /** Every sample in open air, and the aim visible from the first, middle and last. */
    public static boolean clear(ClientLevel level, Entity viewer, List<CarriageOcclusion.Carriage> carriages,
                                List<Vector3d> path, Vector3d aim) {
        if (path.isEmpty()) return false;
        for (Vector3d p : path) {
            if (insideBlock(level, carriages, p)) return false;
        }
        int[] checks = {0, path.size() / 2, path.size() - 1};
        for (int i : checks) {
            if (!clearView(level, viewer, carriages, aim, path.get(i))) return false;
        }
        return true;
    }

    /** Inside a world block with a collision shape, or inside a carriage block. */
    public static boolean insideBlock(ClientLevel level, List<CarriageOcclusion.Carriage> carriages, Vector3d p) {
        BlockPos pos = BlockPos.containing(p.x, p.y, p.z);
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return true;
        return CarriageOcclusion.insideBlock(carriages, new Vec3(p.x, p.y, p.z));
    }

    /** Can a lens at {@code lens} see {@code subject} (its chest height), past world blocks and carriage walls? */
    public static boolean clearView(ClientLevel level, Entity viewer, List<CarriageOcclusion.Carriage> carriages,
                                    Vector3d subject, Vector3d lens) {
        Vec3 from = new Vec3(subject.x, subject.y + 1.2, subject.z);
        Vec3 to = new Vec3(lens.x, lens.y, lens.z);
        Vec3 clipped = SnapshotCamera.clipTowardOpenAir(level, viewer, from, to);
        if (clipped.distanceToSqr(to) > 0.25) return false;
        return CarriageOcclusion.firstHit(carriages, from, to) == null;
    }
}
