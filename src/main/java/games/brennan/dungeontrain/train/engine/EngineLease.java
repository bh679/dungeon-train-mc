package games.brennan.dungeontrain.train.engine;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;

/**
 * A leased build joining this world: its engine burns one coal on the way in.
 *
 * <p>Worked on the snapshot before it is stamped, so the engine lands holding the right count and a
 * build that lost its engine (or never had one) gets one put back. The burnt count is what the build
 * reports to the relay from here on.</p>
 */
public final class EngineLease {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The snapshot to stamp, and the coal its engine now holds. */
    public record Fuelled(CompoundTag snap, int coal) {}

    private EngineLease() {}

    public static Fuelled burnOnArrival(PoolLease lease, CompoundTag snap) {
        int arriving = DriftEngine.arrivingCoal(lease.fuel(), snap, origin(lease));
        int coal = DriftEngine.burn(arriving);
        LOGGER.debug("[DungeonTrain] drifting build id={} arrives with {} coal, burns one → {}{}",
                lease.id(), arriving, coal, lease.fuel() < 0 ? " (no relay count)" : "");
        return new Fuelled(DriftEngine.withEngineCoal(snap, coal), coal);
    }

    /** The build as first uploaded, which the relay sends only when it has no fuel count; else null. */
    private static CompoundTag origin(PoolLease lease) {
        if (lease.fuel() >= 0 || lease.originBlocks().isEmpty()) return null;
        try {
            return CarriageBlockSnapshot.decode(lease.originBlocks());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] drifting build id={} origin blob unreadable ({}); retrofitting with {} coal.",
                    lease.id(), e.toString(), DriftEngine.FRESH_COAL);
            return null;
        }
    }
}
