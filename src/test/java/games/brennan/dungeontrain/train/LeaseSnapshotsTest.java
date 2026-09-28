package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One lease → one snapshot: the fold rule both a carriage slot and a room stamp rely on. */
class LeaseSnapshotsTest {

    static CompoundTag base(int l, int h, int w, int... cells) {
        CompoundTag root = new CompoundTag();
        root.putInt("v", 2);
        root.putInt("l", l);
        root.putInt("h", h);
        root.putInt("w", w);
        ListTag list = new ListTag();
        for (int i = 0; i + 2 < cells.length; i += 3) {
            CompoundTag cell = new CompoundTag();
            cell.put("p", new IntArrayTag(new int[]{cells[i], cells[i + 1], cells[i + 2]}));
            cell.putString("marker", "m" + (i / 3));
            list.add(cell);
        }
        root.put("cells", list);
        root.put("ents", new ListTag());
        return root;
    }

    static CompoundTag delta(int[] del, int... set) {
        CompoundTag root = new CompoundTag();
        root.putInt("v", 2);
        ListTag s = new ListTag();
        for (int i = 0; i + 2 < set.length; i += 3) {
            CompoundTag cell = new CompoundTag();
            cell.put("p", new IntArrayTag(new int[]{set[i], set[i + 1], set[i + 2]}));
            cell.putString("marker", "set");
            s.add(cell);
        }
        ListTag d = new ListTag();
        if (del != null) d.add(new IntArrayTag(del));
        root.put("set", s);
        root.put("del", d);
        root.put("ents", new ListTag());
        return root;
    }

    static PoolLease leaseOf(CompoundTag base, int baseSeq, List<SharedCarriageClient.DeltaRec> deltas) throws Exception {
        return new PoolLease(9, "tok", CarriageBlockSnapshot.encode(base), base.getInt("l"), base.getInt("h"),
                base.getInt("w"), baseSeq, deltas, "", SharedCarriageClient.Credits.EMPTY,
                SharedCarriageClient.Deaths.EMPTY);
    }

    @Test
    @DisplayName("fold applies only the deltas past baseSeq, in seq order, and seqSeed is the highest seq seen")
    void foldsPendingDeltasInOrder() throws Exception {
        CompoundTag base = base(4, 3, 3, 0, 0, 0, 1, 1, 1);
        List<SharedCarriageClient.DeltaRec> deltas = List.of(
                new SharedCarriageClient.DeltaRec(3, CarriageBlockSnapshot.encode(delta(null, 2, 2, 2))),
                new SharedCarriageClient.DeltaRec(1, CarriageBlockSnapshot.encode(delta(null, 3, 0, 0))), // <= baseSeq: dropped
                new SharedCarriageClient.DeltaRec(2, CarriageBlockSnapshot.encode(delta(new int[]{1, 1, 1}))));
        PoolLease lease = leaseOf(base, 1, deltas);
        CompoundTag folded = LeaseSnapshots.fold(lease);
        ListTag cells = folded.getList("cells", Tag.TAG_COMPOUND);
        assertEquals(2, cells.size(), "base (0,0,0) kept, (1,1,1) deleted by seq 2, (2,2,2) added by seq 3; seq 1 ignored");
        assertEquals(3, LeaseSnapshots.seqSeed(lease));
        assertTrue(LeaseSnapshots.matchesDims(folded, 4, 3, 3));
        assertFalse(LeaseSnapshots.matchesDims(folded, 4, 3, 4));
    }

    @Test
    @DisplayName("A lease with no deltas folds to its base and seeds at baseSeq")
    void noDeltasIsTheBase() throws Exception {
        CompoundTag base = base(4, 3, 3, 0, 0, 0);
        PoolLease lease = leaseOf(base, 7, List.of());
        assertEquals(1, LeaseSnapshots.fold(lease).getList("cells", Tag.TAG_COMPOUND).size());
        assertEquals(7, LeaseSnapshots.seqSeed(lease));
    }
}
