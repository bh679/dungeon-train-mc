package games.brennan.dungeontrain.client.snapshot;

import games.brennan.dungeontrain.net.SnapshotCuePacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a snapshot camera is allowed to stand, for the shots where "anywhere it can see the player
 * from" is not good enough.
 *
 * <p>The case this exists for is the {@code THRESHOLD} photo of a Train Dimension. An endless room
 * is one room tiled edge to edge with the seams carved open, and the twin corridors stand inside the
 * tile they arrive in. {@link SnapshotCamera} only asks whether it can see the player from a spot,
 * and it can see them perfectly well from the copy next door, or from inside a corridor whose whole
 * job is to look right from one side only. Both make a photo of the machinery rather than of the
 * room, so those spots are ruled out by geometry the client cannot derive for itself — the server
 * sends it with the cue ({@link SnapshotCuePacket}).</p>
 *
 * <p>{@link #NONE} allows everywhere, which is what every other tag uses.</p>
 *
 * @param confine the camera must be inside this box, or null to allow anywhere
 * @param exclude the camera must be outside every one of these
 */
public record SnapshotFraming(AABB confine, List<AABB> exclude) {

    /** No constraint — the camera goes wherever it can see the subject from. */
    public static final SnapshotFraming NONE = new SnapshotFraming(null, List.of());

    /**
     * Keep the camera this far inside the confining box. Without it the lens can sit flush against
     * the room's inner wall face, which renders as a wall filling the frame.
     */
    private static final double INSET = 0.5;

    public SnapshotFraming {
        exclude = List.copyOf(exclude);
    }

    /** Build from a cue packet's boxes; {@link #NONE} when it carried no constraints. */
    public static SnapshotFraming of(SnapshotCuePacket.Box confine, List<SnapshotCuePacket.Box> exclude) {
        if (confine == null && (exclude == null || exclude.isEmpty())) return NONE;
        List<AABB> boxes = new ArrayList<>();
        if (exclude != null) {
            for (SnapshotCuePacket.Box box : exclude) boxes.add(box.toAabb());
        }
        return new SnapshotFraming(confine == null ? null : confine.toAabb(), boxes);
    }

    /** Is {@code pos} somewhere the camera may stand? */
    public boolean allows(Vec3 pos) {
        if (confine != null && !confine.deflate(INSET).contains(pos)) return false;
        for (AABB box : exclude) {
            if (box.contains(pos)) return false;
        }
        return true;
    }
}
