package games.brennan.dungeontrain.portal.tribute;

import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCarriageLayout;
import games.brennan.dungeontrain.portal.PortalStructure;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every tribute room standing on a live train right now, by pair key: where its room box is and whose
 * it is. What the build rule ({@code TributeRoomGuard}) asks: look-only, except for its owner.
 *
 * <p>Server memory, kept in step with the portal structures: written when a pair is stamped
 * (relocations included) and dropped when the structure goes.</p>
 */
public final class TributeRoomRegistry {

    /** One standing tribute room. */
    public record Standing(int pairKey, ResourceKey<Level> dimension, BoundingBox room, String owner, String key) {

        /** The room's lowest corner: where its room-local offsets count from. */
        public BlockPos origin() {
            return new BlockPos(room.minX(), room.minY(), room.minZ());
        }

        public Vec3i size() {
            return new Vec3i(room.getXSpan(), room.getYSpan(), room.getZSpan());
        }
    }

    private static final Map<Integer, Standing> STANDING = new ConcurrentHashMap<>();
    /**
     * Whose room each pair last stood, kept after the pair goes. A room a player has been in is carried
     * (or remembered) as a bare live copy with no owner; this puts the owner back on it, so it is still
     * theirs to build in, and still dressed and tuned as theirs, wherever it is stamped next.
     */
    private static final Map<Integer, String> OWNER_BY_PAIR = new ConcurrentHashMap<>();

    private TributeRoomRegistry() {}

    /** A pair's structure is down: remember it when it is a tribute room, forget it when it is not. */
    public static void onStructureStamped(ServerLevel level, int pairKey, PortalStructure structure, CarriageDims dims) {
        if (!TributeRooms.TEMPLATE.equals(structure.roomName())) {
            STANDING.remove(pairKey);
            TributeRoomLiveEdits.onRoomChanged(pairKey, "");
            return;
        }
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        BlockPos origin = structure.roomOrigin(dims, layout);
        Vec3i size = structure.roomSize();
        BoundingBox room = BoundingBox.fromCorners(origin, origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1));
        TributeRoomFloor.lay(level, origin, size);
        String owner = structure.blob() == null ? "" : structure.blob().owner();
        String key = TributeRoomKeys.keyFor(level, null);
        STANDING.put(pairKey, new Standing(pairKey, level.dimension(), room, owner, key));
        if (!owner.isEmpty()) OWNER_BY_PAIR.put(pairKey, owner);
        // The same owner's room moved on keeps what they placed in it; anything else starts afresh.
        TributeRoomLiveEdits.onRoomChanged(pairKey, owner);
        if (!owner.isEmpty()) {
            String name = TributeRoomStore.loadByOwner(owner, key).map(TributeRoomBlob::ownerName)
                .filter(n -> !n.isBlank()).orElseGet(() -> TributeRoomHeads.nameFor(level, owner));
            TributeRoomHeads.dress(level, origin, size, owner, name);
            games.brennan.dungeontrain.compat.vista.TvBlocks.tuneTributeTvs(level, room);
        }
        TributeTvButton.place(level, room);
    }

    /** The pair's room is about to be erased: what its owner built is saved first, while it can be read. */
    public static void onStructureGone(int pairKey) {
        TributeRoomLiveEdits.onRoomGone(pairKey);
        STANDING.remove(pairKey);
    }

    /** The room standing for {@code pairKey}, or null. */
    @Nullable
    public static Standing byPair(int pairKey) {
        return STANDING.get(pairKey);
    }

    /**
     * {@code blob} with the pair's last owner put back on it, when it is a tribute room carried or
     * remembered as a bare live copy; {@code blob} itself otherwise (null stays null).
     */
    @Nullable
    public static games.brennan.dungeontrain.portal.PortalRoomBlob withOwner(int pairKey, PortalStructure planned,
                                                                         @Nullable games.brennan.dungeontrain.portal.PortalRoomBlob blob) {
        if (blob == null || !blob.owner().isEmpty() || blob.isLeased() || !TributeRooms.TEMPLATE.equals(planned.roomName())) return blob;
        String owner = OWNER_BY_PAIR.get(pairKey);
        return owner == null ? blob : games.brennan.dungeontrain.portal.PortalRoomBlob.live(blob.snapshot(), owner);
    }

    public static boolean isEmpty() {
        return STANDING.isEmpty();
    }

    /** The tribute room {@code pos} is inside, or null. */
    @Nullable
    public static Standing at(ResourceKey<Level> dimension, BlockPos pos) {
        if (STANDING.isEmpty()) return null;
        for (Standing s : STANDING.values()) {
            if (s.dimension().equals(dimension) && s.room().isInside(pos)) return s;
        }
        return null;
    }

    public static void clear() {
        STANDING.clear();
        OWNER_BY_PAIR.clear();
    }
}
