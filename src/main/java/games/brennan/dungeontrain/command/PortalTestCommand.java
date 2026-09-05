package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.PortalRoomEditor;
import games.brennan.dungeontrain.event.PortalCarriageEvents;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PortalTestSessionPacket;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCarriageLayout;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorKind;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.portal.PortalRoomDoorCells;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.portal.PortalRoomSizes;
import games.brennan.dungeontrain.portal.PortalRoomTiling;
import games.brennan.dungeontrain.portal.PortalStructure;
import games.brennan.dungeontrain.portal.PortalTestSession;
import games.brennan.dungeontrain.portal.PortalTwinLanes;
import games.brennan.dungeontrain.portal.PortalTwinRegion;
import games.brennan.dungeontrain.portal.PortalTwinSpace;
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
            .executes(ctx -> runTest(ctx.getSource(), null))
            .then(Commands.literal("back").executes(ctx -> runBack(ctx.getSource())))
            // Naming the room tests one the author is not standing in — what the X menu's button
            // sends, since a tile can be selected from anywhere in the browser.
            .then(Commands.argument("room", StringArgumentType.word())
                .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                    games.brennan.dungeontrain.track.variant.TrackVariantRegistry.namesFor(
                        games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM), builder))
                .executes(ctx -> runTest(ctx.getSource(), StringArgumentType.getString(ctx, "room"))));
    }

    /**
     * Stand up {@code roomName}, or the room whose plot the player is standing in when it is null.
     *
     * <p>Standing in it was once the only way to name one, which made Test the Carriage unreachable
     * for a tile selected from across the browser — the room is stamped in its own band in the
     * basement either way, and where the author happens to be standing has never been part of what
     * it tests.</p>
     */
    private static int runTest(CommandSourceStack source, String roomArg) {
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

        String roomName;
        if (roomArg != null && !roomArg.isBlank()) {
            roomName = games.brennan.dungeontrain.track.variant.TrackVariantRegistry
                .find(games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM, roomArg)
                .orElse(null);
            if (roomName == null) {
                source.sendFailure(Component.literal("Unknown dimensional carriage '" + roomArg + "'.")
                    .withStyle(ChatFormatting.RED));
                return 0;
            }
        } else {
            roomName = PortalRoomEditor.plotContaining(player.blockPosition(), dims);
            if (roomName == null) {
                source.sendFailure(Component.literal(
                    "Name a dimensional carriage to test, or stand in one's plot — "
                        + "/dungeontrain portal test <room>.").withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        // The room as authored, so what is tested is what was built: its own size and its own
        // settings (walls mode, contents, books), not the defaults. Its HEIGHT is held to what this
        // world's basement can stand up, exactly as PortalCarriageBuilder.planStructure holds it in
        // play: a test that stamped a taller room than a player will ever meet would be testing a
        // room that does not exist, and would push it up through the bedrock to do so.
        //
        // The basement rather than PortalTwinSpace.regionFor, because the basement is the region this
        // command actually stamps into — originFor stands the lane on the build floor.
        Vec3i authoredSize = PortalRoomSizes.sizeOf(roomName, dims);
        PortalRoomSettings authored = PortalRoomSettings.of(roomName);
        PortalTwinRegion region = PortalTwinSpace.basementOf(overworld);
        Vec3i roomSize = PortalCarriageBuilder.heldInRegion(region, authoredSize);

        // The one thing a twin has to do is fit in the space it is stamped into, and this command
        // never asked. In play a pair that does not fit simply goes without a twin
        // (PortalCarriageEvents.ensureStructure); here an author asked out loud, so answer them —
        // rather than stamping through the world's floor and failing somewhere in the block writes.
        int structureHeight = Math.max(dims.height(), roomSize.getY());
        int twinY = PortalTwinLanes.floorY(region.base());
        if (!PortalTwinLanes.fitsUnderWorld(region.base(), region.ceiling(), twinY, structureHeight)) {
            source.sendFailure(Component.literal(
                "'" + roomName + "' needs " + structureHeight + " blocks and the sealed space under "
                    + "this world's bedrock only has "
                    + PortalTwinLanes.maxStructureHeight(region.base(), region.ceiling())
                    + ". Make it shorter to test it here.").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (roomSize.getY() < authoredSize.getY()) {
            int held = roomSize.getY();
            source.sendSuccess(() -> Component.literal(
                "'" + roomName + "' is " + authoredSize.getY() + " tall and this world can only stand up "
                    + held + " — testing it at that height, which is what a player would walk into."
            ).withStyle(ChatFormatting.YELLOW), false);
        }
        // A chunk dimension stands its doorways on the ground its sample landed, so there is nothing
        // to stamp until that sample is in hand. In play the pair simply waits a tick; an author who
        // asked out loud gets told, and the sampling they just started is finished by the time they
        // read the message.
        PortalRoomSettings settings = authored;
        if (authored.mode().generatesTerrain()) {
            games.brennan.dungeontrain.portal.PortalChunkSlice slice =
                games.brennan.dungeontrain.portal.PortalChunkTerrain.slice(
                    overworld, PortalTestSession.PAIR_KEY, roomName);
            if (slice == null) {
                source.sendFailure(Component.literal(
                    "'" + roomName + "' is still sampling its chunk of world generation — run this "
                        + "again in a second.").withStyle(ChatFormatting.YELLOW));
                return 0;
            }
            settings = games.brennan.dungeontrain.portal.PortalChunkDoors.fit(authored, slice, dims,
                PortalCarriageBuilder.layoutFor(dims, PortalCorridorKind.DEFAULT), roomSize);
        }

        // Both entry-door offsets, clamped exactly as roomOrigin will clamp them: the raw authored
        // values can sit outside what this room's width and height can spend, and re-deriving either
        // the stamp height or the doorway from an unclamped number lands beside the opening rather
        // than in it. The exit door's offsets are deliberately not read here — a test session
        // arrives at the entry mouth, and the exit corridor can never hang below the room's floor.
        int doorOffset = PortalRoomLayout.clampDoorOffset(
            dims, roomSize.getZ(), settings.doorOffset().value());
        int doorHeightOffset = PortalRoomLayout.clampDoorHeightOffset(
            dims, roomSize.getY(), settings.doorHeightOffset().value());

        PortalStructure structure = new PortalStructure(
            originFor(player, overworld, doorHeightOffset), roomName, roomSize, settings,
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
            // The CORRIDOR's floor, which is the room's own only while the door sits at it.
            roomOrigin.getY() + doorHeightOffset + 1,
            // roomOrigin already has the door offset baked into where it sits — the SAME offset has
            // to be handed to doorZ, or this re-derives a symmetric centre off a box that is no
            // longer symmetric about the corridor and lands off the real doorway.
            PortalRoomDoorCells.doorZ(roomOrigin, roomSize, doorOffset));

        GameType previous = player.gameMode.getGameModeForPlayer();
        PortalTestSession.put(player.getUUID(), new PortalTestSession.Session(
            player.level().dimension(), player.position(), player.getYRot(), player.getXRot(),
            previous, structure, roomName, arrival));
        if (previous != GameType.CREATIVE) player.setGameMode(GameType.CREATIVE);

        player.teleportTo(overworld, arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5,
            FACE_EAST, 0.0f);
        DungeonTrainNet.sendTo(player, new PortalTestSessionPacket(true, roomName));
        // The room's own light. In play PortalCarriageEvents sends this to whoever stands inside a
        // live pair's room, on the same box; a test session is not a pair, so nothing there sees
        // it, and a room set to Daylight tested dark — which is precisely what the test is for.
        sendSky(player, dims, layout, structure);

        LOGGER.info("[DungeonTrain] portal test: stamped '{}' ({}x{}x{}) at {} for {} — arrival {}",
            roomName, roomSize.getX(), roomSize.getY(), roomSize.getZ(), structure.origin(),
            player.getName().getString(), arrival);

        source.sendSuccess(() -> Component.literal(
            "You're in the doorway of '" + roomName + "' — a corridor each side, no train attached. "
                + "Back in the menu returns you to the plot.").withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** The same lift the live path sends — {@code PortalCarriageEvents.sendSkyFor} — for this one player. */
    private static void sendSky(ServerPlayer player, CarriageDims dims, PortalCarriageLayout layout,
                                PortalStructure structure) {
        games.brennan.dungeontrain.portal.PortalRoomSky sky = structure.settings().sky();
        if (!sky.lights() || !games.brennan.dungeontrain.config.DungeonTrainConfig.isPortalRoomDaylight()) return;
        BlockPos roomOrigin = structure.roomOrigin(dims, layout);
        DungeonTrainNet.sendTo(player, games.brennan.dungeontrain.net.PortalRoomSkyPacket.inWorld(
            structure.tiledMinX(dims, layout), roomOrigin.getY(), structure.tiledMinZ(dims, layout),
            structure.tiledMaxX(dims, layout), roomOrigin.getY() + structure.roomSize().getY() - 1,
            structure.tiledMaxZ(dims, layout), sky.ordinal()));
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
        DungeonTrainNet.sendTo(player, games.brennan.dungeontrain.net.PortalRoomSkyPacket.none());

        // Take the room's fog, daylight, train audio and depth disguise back. The same call the
        // ticker makes, run once more now that the player is standing outside the structure: the send
        // pass finds nobody inside and the clear pass lifts all four. Without it the ticker simply
        // stops — the session is gone — and they would walk around the plot still fogged.
        //
        // groundY only reaches the send pass, which finds nobody; it is read off the world anyway so
        // that this call cannot be the one that disagrees with the ticker about the disguise.
        PortalCarriageEvents.sendRoomAmbience(dims,
            PortalCarriageBuilder.layoutFor(dims, session.structure().kind()),
            session.structure(),
            DungeonTrainWorldData.get(overworld).getTrainY(),
            java.util.List.of(player));

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
     *
     * <h2>{@code doorHeightOffset} is what stands the room ON the lane floor</h2>
     * <p>This returns the <b>corridor lane</b>, and a room spends its door-height offset by dropping
     * its own floor below that line ({@link games.brennan.dungeontrain.portal.PortalRoomLayout#roomOrigin}).
     * The lowest lane's floor is {@link PortalTwinLanes#FLOOR_MARGIN} blocks over the bottom of the
     * world, so a room that hung below it hung out of the world — and {@code setBlock} out of range
     * is a <b>silent no-op</b>, so the bottom of the author's build was quietly never written. A room
     * with a 47-block offset lost 46 of its 70 rows and stood on nothing.</p>
     *
     * <p>Lifting the lane by the offset puts the room's floor exactly on the lane floor, so the
     * structure occupies {@code [laneFloor, laneFloor + roomHeight]} — which is the span a lane is
     * sized for in the first place ({@link PortalTwinLanes#laneHeight}), and what every room without
     * an offset has always occupied. Clamped by the caller, because an unclamped offset would lift
     * the structure further than the room's own height can spend.</p>
     */
    private static BlockPos originFor(ServerPlayer player, ServerLevel level, int doorHeightOffset) {
        return new BlockPos(
            player.blockPosition().getX(),
            PortalTwinLanes.floorY(level.getMinBuildHeight()) + doorHeightOffset,
            TEST_Z_OFFSET);
    }

    private static String fmt(Vec3 v) {
        return String.format("(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }
}
