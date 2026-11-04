package games.brennan.dungeontrain.portal.home;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalStructure;
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
