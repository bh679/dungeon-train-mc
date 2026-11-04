package games.brennan.dungeontrain.portal.home;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.PortalRoomTemplateStore;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.template.TemplateDecor;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The death visit's own way in and out: the {@value #ENTRANCE} and {@value #EXIT} templates, stood
 * end-on against the home room's two doors in place of the pair corridors a room gets on the train.
 *
 * <p>Both are portal-room templates (so the Portals editor lists them under {@code home} and an
 * author can reshape them), weighted 0 so the train lottery never stands one as a room. Each is
 * centred on the room's door line and shares its floor row, so a door cut at the room's centre
 * opens straight into the tube. The geometry is pure and unit-tested; only {@link #stamp} touches a
 * level.</p>
 */
public final class HomeRoomCorridors {

    public static final String ENTRANCE = "home_entrance";
    public static final String EXIT = "home_exit";

    private static final Logger LOGGER = LogUtils.getLogger();

    private HomeRoomCorridors() {}

    /** Where a tube of {@code size} stands against the room's low-x door: its end column one before the room. */
    public static BlockPos entranceOrigin(BlockPos roomOrigin, Vec3i roomSize, Vec3i size) {
        return new BlockPos(roomOrigin.getX() - size.getX(), roomOrigin.getY(), centredZ(roomOrigin, roomSize, size));
    }

    /** Where a tube of {@code size} stands against the room's high-x door: starting one past the room. */
    public static BlockPos exitOrigin(BlockPos roomOrigin, Vec3i roomSize, Vec3i size) {
        return new BlockPos(roomOrigin.getX() + roomSize.getX(), roomOrigin.getY(), centredZ(roomOrigin, roomSize, size));
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
        List<BoundingBox> laid = new ArrayList<>(2);
        layTube(level, ENTRANCE, dims, roomOrigin, roomSize, true).ifPresent(laid::add);
        layTube(level, EXIT, dims, roomOrigin, roomSize, false).ifPresent(laid::add);
        return laid;
    }

    private static Optional<BoundingBox> layTube(ServerLevel level, String name, CarriageDims dims,
                                                 BlockPos roomOrigin, Vec3i roomSize, boolean entrance) {
        Optional<StructureTemplate> template = PortalRoomTemplateStore.get(level, name, dims);
        if (template.isEmpty()) {
            LOGGER.warn("[DungeonTrain] home room: no '{}' template — the visit has no {} passage", name, entrance ? "entrance" : "exit");
            return Optional.empty();
        }
        Vec3i size = template.get().getSize();
        BlockPos origin = entrance ? entranceOrigin(roomOrigin, roomSize, size) : exitOrigin(roomOrigin, roomSize, size);
        BoundingBox box = boxOf(origin, size);
        PortalClear.clearBox(level, box, PortalCorridorMask.NONE);
        CarriagePlacer.stampTemplateAt(level, origin, template.get(), null, /*relight*/ true, TemplateDecor.Rule.ROOM);
        return Optional.of(box);
    }
}
