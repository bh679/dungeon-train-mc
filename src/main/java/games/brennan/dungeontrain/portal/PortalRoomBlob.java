package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Credits;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Deaths;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import games.brennan.dungeontrain.train.LeaseSnapshots;
import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.util.Objects;

/**
 * A room's blocks as one captured snapshot, standing in for its template on the next stamp.
 *
 * <p>Two things put one on a {@link PortalStructure}. A <b>lease</b> from the shared-carriage relay:
 * another world's edited copy of the same room, which is stamped verbatim instead of the template and
 * carries the relay identity the world then writes its own edits back through. And a <b>live
 * capture</b> of this world's own room, taken just before a relocation erases it — a room somebody
 * has built in must be re-laid as they left it, not re-rolled from the template. The two stamp
 * identically; only {@link #relayId} tells them apart, and only the registry cares.</p>
 *
 * @param snapshot the folded {@code CarriageBlockSnapshot} tag: the base blob with every pending
 *                 delta applied, so {@code l/h/w} are the room's box
 * @param relayId  the relay row, or null for a live capture of a room this world stamped itself
 * @param token    the lease token that authorises writing back, or null with {@code relayId}
 * @param owner    dashless uuid of the relay-recorded author, "" when unknown
 * @param seqSeed  the delta floor the room's own edits must clear
 */
public record PortalRoomBlob(CompoundTag snapshot, Integer relayId, String token, String owner,
                             Credits credits, Deaths deaths, int seqSeed, boolean authoredHere) {

    public PortalRoomBlob {
        Objects.requireNonNull(snapshot, "snapshot");
        if (owner == null) owner = "";
        if (credits == null) credits = Credits.EMPTY;
        if (deaths == null) deaths = Deaths.EMPTY;
    }

    /**
     * The blob a relay lease becomes, with its delta log folded on.
     *
     * @throws IOException when the base blob cannot be decoded — the lease is unusable and the
     *                     caller hands it back
     */
    public static PortalRoomBlob fromLease(PoolLease lease, boolean authoredHere) throws IOException {
        return new PortalRoomBlob(LeaseSnapshots.fold(lease), lease.id(), lease.token(), lease.owner(),
            lease.credits(), lease.deaths(), LeaseSnapshots.seqSeed(lease), authoredHere);
    }

    /** A live capture of this world's own room, carried across a relocation. */
    public static PortalRoomBlob live(CompoundTag snapshot) {
        return new PortalRoomBlob(snapshot, null, null, "", Credits.EMPTY, Deaths.EMPTY, 0, false);
    }

    /** True when this blob came from the relay and carries a lease to write back through. */
    public boolean isLeased() {
        return relayId != null && token != null;
    }

    /** Whether the snapshot's box is exactly {@code l × h × w}. */
    public boolean matches(int l, int h, int w) {
        return LeaseSnapshots.matchesDims(snapshot, l, h, w);
    }
}
