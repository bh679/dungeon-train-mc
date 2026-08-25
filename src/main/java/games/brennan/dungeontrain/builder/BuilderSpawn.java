package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.portal.PortalRoomSizes;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;

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
        // What is parked, not what the mode would park: opening a room or a part parks one carriage
        // where a whole carriage parks three, and the standoff is sized from the train that is
        // actually out there.
        return BuilderWorldLayout.spawnPos(dims, BuilderWorldSetup.parkedCarriages(data));
    }

    /**
     * Leave the player hovering rather than falling, on arrival in a builder world.
     *
     * <p>The world spawn is anchored at server start, <em>before</em> the client has said which mode
     * was picked — so it cannot know a Train Dimensions world will have no platform, and it puts the
     * player at the standoff over open void. {@code BuilderSetupPacket} re-anchors a moment later,
     * and this is what makes the gap between the two harmless.</p>
     *
     * <p><b>Creative is not protection from the void.</b> It removes fall damage, which is why this
     * looked survivable; the player still falls, and falling out of the bottom of the world kills
     * them regardless of game mode.</p>
     *
     * <p>Sets {@code flying}, never {@code mayfly}. A builder world is created
     * {@link net.minecraft.world.level.GameType#CREATIVE} so the permission is already there; if it
     * somehow isn't, the honest response is to leave the abilities alone rather than grant something
     * the game mode withheld — and a granted {@code mayfly} would be reverted by the next
     * {@code onUpdateAbilities} a game-mode change triggers, making it a bug that only appears
     * later.</p>
     */
    public static void startFlying(ServerPlayer player) {
        if (player == null
                || !player.serverLevel().dimensionTypeRegistration()
                        .is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return;
        }
        Abilities abilities = player.getAbilities();
        if (!abilities.mayfly || abilities.flying) {
            return;
        }
        abilities.flying = true;
        player.onUpdateAbilities();
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
