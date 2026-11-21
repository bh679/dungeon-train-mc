package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.net.DeathScenePacket;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.PlacedCarriageFacts;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.primitives.AABBdc;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Features of the train near a dead player worth a tracking shot: carriages with a placed contents
 * template (closest first), then whole carriage groups, within the three groups nearest the body.
 * Each target is a point in its sub-level's plot space so the client can follow it through the
 * sub-level's live pose as it rides past. Server only.
 */
public final class DeathSceneTargets {

    /** How many groups either side of the body's group are considered. */
    static final int GROUPS_CONSIDERED = 3;

    private DeathSceneTargets() {}

    public static List<DeathScenePacket.Target> near(ServerLevel level, Vec3 deathPos) {
        Trains.Carriage own = null;
        List<Trains.Carriage> train = null;
        double best = Double.MAX_VALUE;
        for (List<Trains.Carriage> candidate : Trains.byTrainId(level).values()) {
            for (Trains.Carriage c : candidate) {
                AABBdc box = c.ship().worldAABB();
                double d = distSq(box, deathPos);
                if (d < best) {
                    best = d;
                    own = c;
                    train = candidate;
                }
            }
        }
        if (own == null || best > 64.0 * 64.0) return List.of();
        int ownPIdx = own.provider().getPIdx();
        List<Trains.Carriage> nearest = new ArrayList<>(train);
        nearest.sort(Comparator.comparingInt(c -> Math.abs(c.provider().getPIdx() - ownPIdx)));
        List<DeathScenePacket.Target> contents = new ArrayList<>();
        List<DeathScenePacket.Target> groups = new ArrayList<>();
        for (Trains.Carriage group : nearest.subList(0, Math.min(GROUPS_CONSIDERED, nearest.size()))) {
            UUID id = group.ship().subLevelId();
            TrainTransformProvider p = group.provider();
            for (int pIdx = p.getPIdx(); pIdx <= p.getGroupHighestPIdx(); pIdx++) {
                PlacedCarriageFacts.Facts facts = PlacedCarriageFacts.get(pIdx);
                if (facts == null || !hasContents(facts.contentsId())) continue;
                Vector3d c = slotCentre(p, pIdx);
                contents.add(new DeathScenePacket.Target(id, c.x, c.y, c.z, DeathScenePacket.Target.Kind.CONTENTS));
            }
            AABBdc box = group.ship().worldAABB();
            Vector3d centre = group.ship().worldToShip(new Vector3d(
                (box.minX() + box.maxX()) / 2.0, (box.minY() + box.maxY()) / 2.0, (box.minZ() + box.maxZ()) / 2.0));
            groups.add(new DeathScenePacket.Target(id, centre.x, centre.y, centre.z, DeathScenePacket.Target.Kind.GROUP));
        }
        List<DeathScenePacket.Target> out = new ArrayList<>(contents);
        out.addAll(groups);
        return List.copyOf(out.subList(0, Math.min(out.size(), DeathScenePacket.MAX_TARGETS)));
    }

    /** Pure: a recorded contents id that names a template (not blank, not a shell-only marker). */
    static boolean hasContents(String contentsId) {
        return contentsId != null && !contentsId.isBlank() && !contentsId.startsWith("(");
    }

    /** Pure: the centre of carriage {@code pIdx}'s slot in the group's plot space. */
    static Vector3d slotCentre(BlockPos origin, CarriageDims dims, int groupPIdx, int groupSize, int pIdx) {
        int enclosedStartOffset = groupSize > 1 ? CarriagePlacer.halfPadLen(dims) : 0;
        double x = origin.getX() + enclosedStartOffset + (pIdx - groupPIdx) * dims.length() + dims.length() / 2.0;
        return new Vector3d(x, origin.getY() + dims.height() / 2.0, origin.getZ() + dims.width() / 2.0);
    }

    private static Vector3d slotCentre(TrainTransformProvider p, int pIdx) {
        return slotCentre(p.getShipyardOrigin(), p.dims(), p.getPIdx(), p.getGroupSize(), pIdx);
    }

    private static double distSq(AABBdc box, Vec3 p) {
        double dx = Math.max(Math.max(box.minX() - p.x, 0.0), p.x - box.maxX());
        double dy = Math.max(Math.max(box.minY() - p.y, 0.0), p.y - box.maxY());
        double dz = Math.max(Math.max(box.minZ() - p.z, 0.0), p.z - box.maxZ());
        return dx * dx + dy * dy + dz * dz;
    }
}
