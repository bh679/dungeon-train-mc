package games.brennan.dungeontrain.portal.home;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * A pair that rolled the home room stands a player's saved room rather than the bare template —
 * one drawn by {@link HomeRoomLottery} from this server's store. With nothing saved yet the template
 * itself stands, which is what every player's room starts as.
 */
public final class HomeRoomPlacement {

    private static final Logger LOGGER = LogUtils.getLogger();

    private HomeRoomPlacement() {}

    /**
     * A random saved player's room at {@code size} — the editor's test of the home room draws one
     * afresh each press — or the template as {@code player}'s own default when nobody has one.
     */
    public static Optional<HomeRoomBlob> randomSavedRoom(ServerLevel level, net.minecraft.server.level.ServerPlayer player,
                                                         games.brennan.dungeontrain.train.CarriageDims dims, Vec3i size) {
        List<String> owners = HomeRoomStore.owners();
        if (!owners.isEmpty()) {
            String owner = owners.get(level.random.nextInt(owners.size()));
            Optional<HomeRoomBlob> blob = HomeRoomStore.loadByOwner(owner);
            if (blob.isPresent() && blob.get().matches(size)) return blob;
        }
        return HomeRoomStore.defaultFor(level, player, dims).filter(b -> b.matches(size));
    }

    /** Tell {@code player} what the TV of the room at {@code roomOrigin} replays, when its owner has a last death. */
    public static void sendReport(ServerLevel level, net.minecraft.server.level.ServerPlayer player, BlockPos roomOrigin,
                                  Vec3i size, HomeRoomBlob blob) {
        if (!blob.hasReport()) return;
        net.minecraft.world.level.levelgen.structure.BoundingBox room = net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
            roomOrigin, roomOrigin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1));
        List<BlockPos> screen = new java.util.ArrayList<>();
        net.minecraft.core.Direction facing = HomeRoomVisitService.screenBlocks(level, room, screen);
        if (screen.isEmpty()) return;
        games.brennan.dungeontrain.net.DungeonTrainNet.sendTo(player, games.brennan.dungeontrain.net.HomeRoomReportPacket.of(
            HomeRoomVisitService.boxOf(screen), facing, blob.ownerName(), blob.reportMessage(), blob.report()));
    }

    /** {@code planned} with a saved room attached when it is a home pair with a room to show; unchanged otherwise. */
    public static PortalStructure withPlayerRoom(ServerLevel level, PortalStructure planned, int pairKey) {
        if (!HomeRooms.TEMPLATE.equals(planned.roomName()) || planned.blob() != null) return planned;
        List<String> owners = HomeRoomStore.owners();
        String owner = HomeRoomLottery.pick(level.getSeed(), pairKey, owners);
        if (owner == null) return planned;
        Optional<HomeRoomBlob> blob = HomeRoomStore.loadByOwner(owner);
        Vec3i size = planned.roomSize();
        if (blob.isEmpty() || !blob.get().matches(size)) {
            LOGGER.info("[DungeonTrain] home room: pair {} drew {} but their room is missing or not {}x{}x{} — the template stands",
                pairKey, owner, size.getX(), size.getY(), size.getZ());
            return planned;
        }
        LOGGER.info("[DungeonTrain] home room: pair {} stands {}'s room (rev {})", pairKey, blob.get().ownerName(), blob.get().revision());
        return planned.withBlob(blob.get().toPortalRoomBlob());
    }
}
