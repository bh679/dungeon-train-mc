package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

/**
 * How a relay lease becomes one snapshot: the base blob with its pending delta log folded on, and
 * the sequence floor the build's own edits must clear afterwards.
 *
 * <p>Shared by the two things that place a lease — a carriage slot ({@code TrainAssembler}) and a
 * dimensional carriage's room ({@code PortalCarriageBuilder.planStructure}) — so the fold rule lives
 * in one place. The relay never parses either blob; it hands back what it holds, already filtered to
 * {@code seq > baseSeq} and ordered, and this is where the mod turns that into a room or a carriage.</p>
 */
public final class LeaseSnapshots {

    private static final Logger LOGGER = LogUtils.getLogger();

    private LeaseSnapshots() {}

    /**
     * The lease's decoded base snapshot with every pending delta folded on, in seq order.
     *
     * @throws IOException when the base blob itself cannot be decoded — the lease is unusable
     */
    public static CompoundTag fold(PoolLease lease) throws IOException {
        return foldDeltas(CarriageBlockSnapshot.decode(lease.blocks()), lease);
    }

    /** Fold a lease's opaque delta log (seq &gt; baseSeq, ascending seq) onto its decoded base snapshot. */
    public static CompoundTag foldDeltas(CompoundTag base, PoolLease lease) {
        List<SharedCarriageClient.DeltaRec> pending =
                SharedCarriageClient.pendingDeltas(lease.deltas(), lease.baseSeq());
        if (pending.isEmpty()) return base;
        CompoundTag folded = base;
        for (SharedCarriageClient.DeltaRec d : pending) {
            try {
                folded = CarriageBlockSnapshot.applyDeltaCells(folded, CarriageBlockSnapshot.decode(d.cells()));
            } catch (Exception e) {
                // One unreadable frame costs that frame, not the build: the base and every other
                // delta still stand, which is what the player was shown last time too.
                LOGGER.warn("[DungeonTrain] leased build id={} delta seq={} decode failed: {}",
                        lease.id(), d.seq(), e.toString());
            }
        }
        return folded;
    }

    /**
     * The delta-sequence floor to seed a leased build with: the max of {@code baseSeq} and every
     * delta seq, so the holder's first upload clears the relay's drop-watermark.
     */
    public static int seqSeed(PoolLease lease) {
        int seed = lease.baseSeq();
        if (lease.deltas() != null) {
            for (SharedCarriageClient.DeltaRec d : lease.deltas()) if (d.seq() > seed) seed = d.seq();
        }
        return seed;
    }

    /** Whether the snapshot's box is exactly {@code l × h × w}. */
    public static boolean matchesDims(CompoundTag snap, int l, int h, int w) {
        return snap.getInt("l") == l && snap.getInt("h") == h && snap.getInt("w") == w;
    }
}
