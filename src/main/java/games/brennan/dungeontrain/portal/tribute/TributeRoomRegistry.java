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
 * it is. What the look-only rule ({@code TributeRoomGuard}) asks.
 *
 * <p>Server memory, kept in step with the portal structures: written when a pair is stamped
 * (relocations included) and dropped when the structure goes.</p>
 */
public final class TributeRoomRegistry {

    /** One standing tribute room. */
    public record Standing(ResourceKey<Level> dimension, BoundingBox room, String owner) {}

    private static final Map<Integer, Standing> STANDING = new ConcurrentHashMap<>();

    private TributeRoomRegistry() {}

    /** A pair's structure is down: remember it when it is a tribute room, forget it when it is not. */
    public static void onStructureStamped(ServerLevel level, int pairKey, PortalStructure structure, CarriageDims dims) {
        if (!TributeRooms.TEMPLATE.equals(structure.roomName())) {
            STANDING.remove(pairKey);
            return;
        }
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        BlockPos origin = structure.roomOrigin(dims, layout);
        Vec3i size = structure.roomSize();
        BoundingBox room = BoundingBox.fromCorners(origin, origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1));
        String owner = structure.blob() == null ? "" : structure.blob().owner();
        STANDING.put(pairKey, new Standing(level.dimension(), room, owner));
        if (!owner.isEmpty()) {
            String name = TributeRoomStore.loadByOwner(owner).map(TributeRoomBlob::ownerName)
                .filter(n -> !n.isBlank()).orElseGet(() -> TributeRoomHeads.nameFor(level, owner));
            TributeRoomHeads.dress(level, origin, size, owner, name);
            games.brennan.dungeontrain.compat.vista.TvBlocks.tuneTributeTvs(level, room);
        }
    }

    public static void onStructureGone(int pairKey) {
        STANDING.remove(pairKey);
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
    }
}
