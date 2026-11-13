package games.brennan.dungeontrain.train.id;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * A drifting build's name id: its carriage id from the world it was first built in. A fresh build takes
 * this world's; a leased one keeps the one the relay hands back, and only a build the relay has none for
 * (stored before name ids) takes this world's, which the relay then keeps. See {@code SharedBuild#nameId}.
 */
public final class BuildNameIds {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BuildNameIds() {}

    /** Carriage {@code pIdx}'s id in this world, or {@code ""} when it can't be worked out. */
    public static String forCarriage(ServerLevel level, int pIdx) {
        try {
            return CarriageIds.idFor(level, pIdx).id().format();
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] no carriage id for pIdx={}: {}", pIdx, e.toString());
            return "";
        }
    }

    /** The name id for a build leased into carriage {@code pIdx}: the relay's, else this world's. */
    public static String forLease(SharedCarriageClient.PoolLease lease, ServerLevel level, int pIdx) {
        return lease.nameId().isEmpty() ? forCarriage(level, pIdx) : lease.nameId();
    }
}
