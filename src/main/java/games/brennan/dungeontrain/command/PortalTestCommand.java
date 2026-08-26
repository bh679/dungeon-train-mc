package games.brennan.dungeontrain.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.PortalRoomEditor;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PortalTestSessionPacket;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCarriageLayout;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.portal.PortalRoomDoorCells;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.portal.PortalRoomSizes;
import games.brennan.dungeontrain.portal.PortalRoomTiling;
import games.brennan.dungeontrain.portal.PortalStructure;
import games.brennan.dungeontrain.portal.PortalTestSession;
import games.brennan.dungeontrain.portal.PortalTwinLanes;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * {@code /dungeontrain portal test} — stand inside the dimensional carriage you are editing, now.
 *
 * <p><b>No train.</b> An earlier version re-seeded one around a forced portal group and asked the
 * author to walk in from a flatbed. That was a great deal of machinery — Sable sub-levels, the
 * rolling window, the corridor swap — for a question that is only ever "does the room I am building
 * look right", and it inherited every one of those parts as a way to fail. What an author walks
 * around in is the <b>twin</b>: ordinary world blocks in sealed space under the bedrock, which
 * {@link PortalCarriageBuilder#stampPairStructure} already lays down whole —
 * {@code [plug][entry corridor][room][exit corridor][plug]}. The corridors either side are clones
 * for context; nothing here is linked to a train and nothing swaps.</p>
 *
 * <p><b>The plot is the argument.</b> The room stamped is whichever one the player is standing in,
 * so there is nothing to pick and nothing to type.</p>
 *
 * <p>{@code back} returns them and sweeps what was stamped. See {@link PortalTestSession}.</p>
 */
public final class PortalTestCommand {

    private static final Logger LOGGER = LogUtils.getLogger();



    /**
     * How far off the track band the test structure is stamped, in blocks of {@code +Z}.
     *
     * <p>Basement space is shared with every live twin, and those sit in their carriage's own chunk
     * columns — which are the track's. Stamping a test structure there could land it on top of one.
     * Well clear on Z means it cannot, whatever the train is doing.</p>
     */
    private static final int TEST_Z_OFFSET = 512;

    /** Facing +X, down the corridor, the way an author arrives from the train in the real thing. */
    private static final float FACE_EAST = -90.0f;

    private PortalTestCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("test")
            .executes(ctx -> runTest(ctx.getSource()))
            .then(Commands.literal("back").executes(ctx -> runBack(ctx.getSource())));
    }

    private static int runTest(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        // Already inside one: stamping a second would leave the first standing and lose the way home
        // to the plot. Send them back first, then in again, so the button is idempotent.
        if (PortalTestSession.has(player.getUUID())) {
            runBack(source);
        }

        String roomName = PortalRoomEditor.plotContaining(player.blockPosition(), dims);
        if (roomName == null) {
            source.sendFailure(Component.literal(
                "Stand in a dimensional carriage plot first — this tests the room you are in. "
                    + "Try /dungeontrain editor portals.").withStyle(ChatFormatting.RED));
            return 0;
        }

        // The room as authored, so what is tested is what was built: its own size and its own
        // settings (walls mode, contents, books), not the defaults.
        Vec3i roomSize = PortalRoomSizes.sizeOf(roomName, dims);
        PortalRoomSettings settings = PortalRoomSettings.of(roomName);
        PortalStructure structure = new PortalStructure(
            originFor(player, overworld, dims), roomName, roomSize, settings,
            // Starts at the base tile and grows from there: PortalTestTicker drives the real
            // PortalRoomTiler around the player, so an endless room repeats here exactly as it does
            // on the train, block variants and all.
            PortalRoomTiling.base(), games.brennan.dungeontrain.portal.PortalExitCopies.NONE,
            PortalRoomTiling.Tile.BASE);

        PortalCarriageBuilder.stampPairStructure(overworld, structure, dims, PortalTestSession.PAIR_KEY);

        // In the ENTRY DOORWAY looking down the room, not dropped in the middle of it — the same
        // view an author gets walking in off the train, which is the one they are building for.
        // Geometry from PortalRoomDoorCells rather than arithmetic here: it is read off the code
        // that actually puts the doors there, and it is swept against PortalRoomLayout for every
        // legal width, so this cannot drift away from where the opening really is.
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        BlockPos roomOrigin = structure.roomOrigin(dims, layout);
        BlockPos arrival = new BlockPos(
            roomOrigin.getX() - 1,
            roomOrigin.getY() + 1,
            PortalRoomDoorCells.doorZ(roomOrigin, roomSize));

        GameType previous = player.gameMode.getGameModeForPlayer();
        PortalTestSession.put(player.getUUID(), new PortalTestSession.Session(
            player.level().dimension(), player.position(), player.getYRot(), player.getXRot(),
            previous, structure, roomName, arrival));
        if (previous != GameType.CREATIVE) player.setGameMode(GameType.CREATIVE);

        player.teleportTo(overworld, arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5,
            FACE_EAST, 0.0f);
        DungeonTrainNet.sendTo(player, new PortalTestSessionPacket(true, roomName));

        LOGGER.info("[DungeonTrain] portal test: stamped '{}' ({}x{}x{}) at {} for {} — arrival {}",
            roomName, roomSize.getX(), roomSize.getY(), roomSize.getZ(), structure.origin(),
            player.getName().getString(), arrival);

        source.sendSuccess(() -> Component.literal(
            "You're in the doorway of '" + roomName + "' — a corridor each side, no train attached. "
                + "Back in the menu returns you to the plot.").withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int runBack(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        PortalTestSession.Session session = PortalTestSession.take(player.getUUID());
        if (session == null) {
            source.sendFailure(Component.literal("You aren't in a test dimensional carriage."));
            return 0;
        }

        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        ServerLevel home = source.getServer().getLevel(session.dimension());
        if (home != null) {
            player.teleportTo(home, session.pos().x, session.pos().y, session.pos().z,
                session.yaw(), session.pitch());
        }
        if (player.gameMode.getGameModeForPlayer() != session.previousGameType()) {
            player.setGameMode(session.previousGameType());
        }
        DungeonTrainNet.sendTo(player, PortalTestSessionPacket.none());

        // Sweep the whole WINDOW, not just the base room. footprintOf is deliberately blind to
        // tiling — copies are laid around the corridors rather than through them — so clearing only
        // that box would leave every copy the ticker grew standing under the world. The structure
        // knows its own tiled bounds; union them with the footprint and clear the lot. Blunt is
        // right here: the test band holds nothing else to protect.
        int cleared = PortalClear.clearBox(overworld, windowBox(overworld, session.structure(), dims),
            PortalCorridorMask.NONE);

        LOGGER.info("[DungeonTrain] portal test back: returned {} to {} and cleared {} block(s) of '{}'",
            player.getName().getString(), fmt(session.pos()), cleared, session.roomName());

        source.sendSuccess(() -> Component.literal(
            "Back at the plot — the test '" + session.roomName() + "' has been cleared away."
        ).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /** The footprint and every copy the tiler grew around it, as one box. */
    private static BoundingBox windowBox(ServerLevel level, PortalStructure structure,
                                         CarriageDims dims) {
        BoundingBox footprint = PortalCarriageBuilder.footprintOf(level, structure, dims);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        return new BoundingBox(
            Math.min(footprint.minX(), structure.tiledMinX(dims, layout) - 1),
            footprint.minY(),
            Math.min(footprint.minZ(), structure.tiledMinZ(dims, layout) - 1),
            Math.max(footprint.maxX(), structure.tiledMaxX(dims, layout) + 1),
            Math.max(footprint.maxY(), structure.origin().getY() + structure.roomSize().getY() + 1),
            Math.max(footprint.maxZ(), structure.tiledMaxZ(dims, layout) + 1));
    }

    /**
     * Where to stamp: the player's X, a lane in this world's basement, and well off the track band.
     *
     * <p>The X is theirs only so a structure is near the region they already have loaded; nothing
     * about it has to line up with anything, because nothing it is stamped beside is a train.</p>
     */
    private static BlockPos originFor(ServerPlayer player, ServerLevel level, CarriageDims dims) {
        return new BlockPos(
            player.blockPosition().getX(),
            PortalTwinLanes.floorY(level.getMinBuildHeight()),
            TEST_Z_OFFSET);
    }

    private static String fmt(Vec3 v) {
        return String.format("(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }
}
