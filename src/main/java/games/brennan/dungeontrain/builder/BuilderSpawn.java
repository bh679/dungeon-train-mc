package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.portal.PortalRoomSizes;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;

/**
 * Where a player stands in a builder world.
 *
 * <p>One question with two answers, which is why it is here rather than repeated at each of the five
 * places that teleport somebody. Standing off the track to look at a train and standing inside the
 * room you are editing are different positions, and the second only became possible when Train
 * Dimensions stopped having a platform to stand on.</p>
 *
 * <p>The distinction is not cosmetic: in a Train Dimensions world
 * {@link BuilderWorldLayout#spawnPos} names a spot in <b>open void</b>, so using it there drops the
 * player out of the world from the first tick.</p>
 */
public final class BuilderSpawn {

    private BuilderSpawn() {}

    /**
     * The spawn for this world as it currently stands: inside the open portal room, or the framing
     * standoff on the platform.
     *
     * <p>Falls back to the standoff when Train Dimensions has no room open yet. That position is
     * still mid-air in a void world, which is the honest answer — there is nothing to stand on until
     * something is opened, and a builder world is creative, so the player flies rather than
     * falls.</p>
     */
    public static BlockPos forLevel(ServerLevel level) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        BuilderMode mode = BuilderMode.fromId(data.builderMode()).orElse(null);

        Vec3i room = openRoomSize(data, dims, mode);
        if (room != null) {
            return BuilderWorldLayout.portalRoomSpawn(room);
        }
        return BuilderWorldLayout.spawnPos(dims, mode == null ? 0 : mode.carriageCount());
    }

    /** The open room's size, or null when this world isn't showing one. */
    private static Vec3i openRoomSize(DungeonTrainWorldData data, CarriageDims dims, BuilderMode mode) {
        if (mode != BuilderMode.TRAIN_DIMENSIONS
                || !BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE.equals(data.builderSubType())) {
            return null;
        }
        String name = data.builderName();
        return name == null || name.isEmpty() ? null : PortalRoomSizes.sizeOf(name, dims);
    }
}
