package games.brennan.dungeontrain.portal.tribute;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.PortalRoomTemplateStore;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.template.TemplateDecor;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The death visit's own way in and out: the {@value #ENTRANCE} template, stood end-on against the
 * tribute room's two doors in place of the pair corridors a room gets on the train — as authored on the
 * entry side, mirrored along X on the exit side, so one tube serves both and an author edits one.
 *
 * <p>It is a portal-room template (so the Portals editor lists it under {@code home}), weighted 0 so
 * the train lottery never stands it as a room. It is centred on the room's door line and shares its
 * floor row, so a door cut at the room's centre opens straight into it. The geometry is pure and
 * unit-tested; only {@link #stamp} touches a level.</p>
 *
 * <p><b>It follows the doors.</b> A room's doors can be raised above its floor or slid off centre
 * ({@link PortalRoomSettings}' door offsets, each end its own); each tube reads its own end's offsets,
 * clamped as {@link PortalRoomLayout} clamps them for the room's box, so it stands flush with the door
 * cut there. Both at zero is the original floor-row, centred placement.</p>
 */
public final class TributeRoomCorridors {

    /** The one tube template: laid as is on the entry side, mirrored along X on the exit side. */
    public static final String ENTRANCE = "tribute_entrance";

    private static final Logger LOGGER = LogUtils.getLogger();

    private TributeRoomCorridors() {}

    /**
     * Where each of the room's two doors sits, as the clamped offsets the room's own box was placed by:
     * Z off the width's centre, Y above the room's bottom edge — per end, since the two may differ.
     */
    public record DoorOffsets(int entryZ, int entryY, int exitZ, int exitY) {
        /** Both doors centred at the room's floor — how every tribute room stood before doors could move. */
        public static final DoorOffsets NONE = new DoorOffsets(0, 0, 0, 0);

        /** The offsets {@code settings} asks for, clamped exactly as the room's box and door cells clamp them. */
        public static DoorOffsets of(PortalRoomSettings settings, CarriageDims dims, Vec3i roomSize) {
            return new DoorOffsets(
                PortalRoomLayout.clampDoorOffset(dims, roomSize.getZ(), settings.doorOffset().value()),
                PortalRoomLayout.clampDoorHeightOffset(dims, roomSize.getY(), settings.doorHeightOffset().value()),
                PortalRoomLayout.clampDoorOffset(dims, roomSize.getZ(), settings.exitDoorOffset().value()),
                PortalRoomLayout.clampDoorHeightOffset(dims, roomSize.getY(), settings.exitDoorHeightOffset().value()));
        }
    }

    /** As {@link #entranceOrigin(BlockPos, Vec3i, Vec3i, DoorOffsets)} for a room whose doors are centred at its floor. */
    public static BlockPos entranceOrigin(BlockPos roomOrigin, Vec3i roomSize, Vec3i size) {
        return entranceOrigin(roomOrigin, roomSize, size, DoorOffsets.NONE);
    }

    /**
     * Where a tube of {@code size} stands against the room's low-x door: its end column one before the
     * room, its floor on the door's own floor row and its centre line on the door's — so a door raised or
     * slid off centre takes the tube with it.
     */
    public static BlockPos entranceOrigin(BlockPos roomOrigin, Vec3i roomSize, Vec3i size, DoorOffsets doors) {
        return new BlockPos(roomOrigin.getX() - size.getX(), roomOrigin.getY() + doors.entryY(),
            centredZ(roomOrigin, roomSize, size) + doors.entryZ());
    }

    /** As {@link #exitOrigin(BlockPos, Vec3i, Vec3i, DoorOffsets)} for a room whose doors are centred at its floor. */
    public static BlockPos exitOrigin(BlockPos roomOrigin, Vec3i roomSize, Vec3i size) {
        return exitOrigin(roomOrigin, roomSize, size, DoorOffsets.NONE);
    }

    /** Where a tube of {@code size} stands against the room's high-x door: starting one past the room, on that door's line. */
    public static BlockPos exitOrigin(BlockPos roomOrigin, Vec3i roomSize, Vec3i size, DoorOffsets doors) {
        return new BlockPos(roomOrigin.getX() + roomSize.getX(), roomOrigin.getY() + doors.exitY(),
            centredZ(roomOrigin, roomSize, size) + doors.exitZ());
    }

    /** The room's door line is its width's centre; a tube of another width is centred on the same line. */
    static int centredZ(BlockPos roomOrigin, Vec3i roomSize, Vec3i size) {
        return roomOrigin.getZ() + (roomSize.getZ() - size.getZ()) / 2;
    }

    public static BoundingBox boxOf(BlockPos origin, Vec3i size) {
        return BoundingBox.fromCorners(origin, origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1));
    }

    /** One box around everything the visit stood, a block of air to spare on every side. */
    public static BoundingBox window(BoundingBox room, List<BoundingBox> tubes) {
        BoundingBox all = new BoundingBox(room.minX(), room.minY(), room.minZ(), room.maxX(), room.maxY(), room.maxZ());
        for (BoundingBox t : tubes) all.encapsulate(t);
        return all.inflatedBy(1);
    }

    /**
     * Lay both tubes against the room at {@code roomOrigin}. The boxes laid come back, so the caller
     * can sweep them later; a tube whose template is missing is skipped with a log line.
     */
    public static List<BoundingBox> stamp(ServerLevel level, BlockPos roomOrigin, Vec3i roomSize, CarriageDims dims) {
        Optional<StructureTemplate> template = PortalRoomTemplateStore.get(level, ENTRANCE, dims);
        if (template.isEmpty()) {
            LOGGER.warn("[DungeonTrain] tribute room: no '{}' template — the visit has no passages", ENTRANCE);
            return List.of();
        }
        Vec3i size = template.get().getSize();
        DoorOffsets doors = doorsOf(roomSize, dims);
        List<BoundingBox> laid = new ArrayList<>(2);
        BlockPos entrance = entranceOrigin(roomOrigin, roomSize, size, doors);
        BoundingBox in = boxOf(entrance, size);
        PortalClear.clearBox(level, in, PortalCorridorMask.NONE);
        CarriagePlacer.stampTemplateAt(level, entrance, template.get(), null, /*relight*/ true, TemplateDecor.Rule.ROOM);
        laid.add(in);
        BlockPos exit = exitOrigin(roomOrigin, roomSize, size, doors);
        BoundingBox out = boxOf(exit, size);
        PortalClear.clearBox(level, out, PortalCorridorMask.NONE);
        // Mirrored along X: the open end that faced the room on the entry side faces it here too.
        CarriagePlacer.stampTemplateMirroredAt(level, exit.offset(size.getX() - 1, 0, 0), template.get(), Mirror.FRONT_BACK, /*relight*/ true, TemplateDecor.Rule.ROOM);
        laid.add(out);
        return laid;
    }

    /** The sweep window of a visit-shaped stamp of {@code structure}: its room and the two tubes. */
    public static BoundingBox windowFor(ServerLevel level, games.brennan.dungeontrain.portal.PortalStructure structure, CarriageDims dims) {
        BlockPos roomOrigin = structure.roomOrigin(dims, games.brennan.dungeontrain.portal.PortalCarriageBuilder.layoutFor(dims, structure.kind()));
        BoundingBox room = boxOf(roomOrigin, structure.roomSize());
        return window(room, boxes(level, roomOrigin, structure.roomSize(), dims));
    }

    /** The boxes {@link #stamp} would lay, for a sweep that has to find them again without a level write. */
    public static List<BoundingBox> boxes(ServerLevel level, BlockPos roomOrigin, Vec3i roomSize, CarriageDims dims) {
        Optional<StructureTemplate> template = PortalRoomTemplateStore.get(level, ENTRANCE, dims);
        if (template.isEmpty()) return List.of();
        Vec3i size = template.get().getSize();
        DoorOffsets doors = doorsOf(roomSize, dims);
        return List.of(boxOf(entranceOrigin(roomOrigin, roomSize, size, doors), size),
            boxOf(exitOrigin(roomOrigin, roomSize, size, doors), size));
    }

    /** The tribute room's doors as its own settings place them — the one source the stamp and the sweep share. */
    private static DoorOffsets doorsOf(Vec3i roomSize, CarriageDims dims) {
        return DoorOffsets.of(PortalRoomSettings.of(TributeRooms.TEMPLATE), dims, roomSize);
    }
}
