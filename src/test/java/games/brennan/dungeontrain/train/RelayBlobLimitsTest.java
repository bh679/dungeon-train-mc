package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.DeltaRec;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Relay blobs are bounded encoded and decoded, per blob and per build. */
class RelayBlobLimitsTest {

    // ---- crafted bombs: written straight to the gzip stream, so nothing big is ever built in memory ----

    /** A root compound holding one byte array that claims {@code length} bytes, all zero. */
    private static String byteArrayBomb(int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(Tag.TAG_COMPOUND);
            out.writeUTF("");
            out.writeByte(Tag.TAG_BYTE_ARRAY);
            out.writeUTF("b");
            out.writeInt(length);
            byte[] zeros = new byte[1 << 16];
            for (long left = length; left > 0; left -= zeros.length) {
                out.write(zeros, 0, (int) Math.min(zeros.length, left));
            }
            out.writeByte(Tag.TAG_END);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /** A root compound whose {@code cells} list holds {@code count} empty compounds: one byte each on the wire. */
    private static String emptyCompoundBomb(int count) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(Tag.TAG_COMPOUND);
            out.writeUTF("");
            out.writeByte(Tag.TAG_LIST);
            out.writeUTF("cells");
            out.writeByte(Tag.TAG_COMPOUND);
            out.writeInt(count);
            byte[] ends = new byte[1 << 16];                 // TAG_END closes each empty compound
            for (long left = count; left > 0; left -= ends.length) {
                out.write(ends, 0, (int) Math.min(ends.length, left));
            }
            out.writeByte(Tag.TAG_END);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /** A {@code l × h × w} box with every cell filled, each holding a block state with properties. */
    private static CompoundTag fullBox(int l, int h, int w) {
        ListTag cells = new ListTag();
        for (int x = 0; x < l; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < w; z++) {
                    CompoundTag state = new CompoundTag();
                    state.putString("Name", "minecraft:oak_stairs");
                    CompoundTag props = new CompoundTag();
                    props.putString("facing", "north");
                    props.putString("half", "bottom");
                    props.putString("shape", "straight");
                    props.putString("waterlogged", "false");
                    state.put("Properties", props);
                    CompoundTag cell = new CompoundTag();
                    cell.put("p", new IntArrayTag(new int[]{x, y, z}));
                    cell.put("s", state);
                    cells.add(cell);
                }
            }
        }
        CompoundTag root = new CompoundTag();
        root.putInt("v", 2);
        root.putInt("l", l);
        root.putInt("h", h);
        root.putInt("w", w);
        root.put("cells", cells);
        root.put("ents", new ListTag());
        return root;
    }

    private static PoolLease lease(String blocks, List<DeltaRec> deltas) {
        return new PoolLease(7, "tok", blocks, 4, 3, 3, 0, deltas, "", SharedCarriageClient.Credits.EMPTY,
                SharedCarriageClient.Deaths.EMPTY);
    }

    // ---- per blob ----

    @Test
    @DisplayName("an over-long blob is refused before it is decoded")
    void overLongStringRefused() {
        String blob = "A".repeat(RelayBlobLimits.MAX_ENCODED_CHARS + 1);
        RelayBlobTooLargeException e = assertThrows(RelayBlobTooLargeException.class,
                () -> CarriageBlockSnapshot.decode(blob));
        assertFalse(e.wholeBuild());
    }

    @Test
    @DisplayName("a byte-array gzip bomb is refused at the accounter, not the heap")
    void byteArrayBombRefused() throws Exception {
        String blob = byteArrayBomb(300 * 1024 * 1024);
        assertTrue(blob.length() <= 700_000, "the bomb fits under the relay's own cap: " + blob.length());
        assertThrows(RelayBlobTooLargeException.class, () -> CarriageBlockSnapshot.decode(blob));
        assertFalse(CarriageBlockSnapshot.withinRelayLimits(blob));
    }

    @Test
    @DisplayName("a many-tiny-tags gzip bomb is refused at the accounter")
    void tagBombRefused() throws Exception {
        String blob = emptyCompoundBomb(20_000_000);
        assertTrue(blob.length() <= 700_000, "the bomb fits under the relay's own cap: " + blob.length());
        assertThrows(RelayBlobTooLargeException.class, () -> CarriageBlockSnapshot.decode(blob));
    }

    @Test
    @DisplayName("a full 32x32x24 carriage and a 49k-cell room decode within the limits")
    void legitimateLargeBuildsDecode() throws Exception {
        for (CompoundTag build : List.of(fullBox(32, 24, 32), fullBox(64, 12, 64))) {
            String blob = CarriageBlockSnapshot.encode(build);
            assertTrue(CarriageBlockSnapshot.withinRelayLimits(blob));
            CompoundTag back = CarriageBlockSnapshot.decode(blob);
            assertEquals(build.getList("cells", Tag.TAG_COMPOUND).size(), back.getList("cells", Tag.TAG_COMPOUND).size());
        }
    }

    @Test
    @DisplayName("corrupt NBT is an IOException, not a size refusal")
    void corruptIsNotOversize() {
        String blob = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5});
        IOException e = assertThrows(IOException.class, () -> CarriageBlockSnapshot.decode(blob));
        assertFalse(e instanceof RelayBlobTooLargeException);
        assertTrue(CarriageBlockSnapshot.withinRelayLimits(blob), "unreadable is not this check's call");
    }

    // ---- per build ----

    @Test
    @DisplayName("one oversize delta is skipped and the rest of the log still folds")
    void oversizeDeltaSkipped() throws Exception {
        CompoundTag base = LeaseSnapshotsTest.base(4, 3, 3, 0, 0, 0);
        List<DeltaRec> deltas = List.of(
                new DeltaRec(1, emptyCompoundBomb(20_000_000)),
                new DeltaRec(2, CarriageBlockSnapshot.encode(LeaseSnapshotsTest.delta(null, 2, 2, 2))));
        CompoundTag folded = LeaseSnapshots.fold(lease(CarriageBlockSnapshot.encode(base), deltas));
        assertEquals(2, folded.getList("cells", Tag.TAG_COMPOUND).size(), "base cell + seq 2's cell");
    }

    @Test
    @DisplayName("a delta log that spends the fold budget refuses the whole build")
    void spentBudgetRefusesBuild() throws Exception {
        List<DeltaRec> deltas = new ArrayList<>();
        for (int seq = 1; seq <= 4; seq++) deltas.add(new DeltaRec(seq, emptyCompoundBomb(20_000_000)));
        PoolLease lease = lease(CarriageBlockSnapshot.encode(LeaseSnapshotsTest.base(4, 3, 3, 0, 0, 0)), deltas);
        RelayBlobTooLargeException e = assertThrows(RelayBlobTooLargeException.class, () -> LeaseSnapshots.fold(lease));
        assertTrue(e.wholeBuild());
    }

    @Test
    @DisplayName("a small budget is shared by the base and its deltas")
    void budgetIsShared() throws Exception {
        CompoundTag base = fullBox(8, 4, 8);
        String blob = CarriageBlockSnapshot.encode(base);
        RelayBlobLimits.FoldBudget probe = new RelayBlobLimits.FoldBudget();
        CarriageBlockSnapshot.decode(blob, probe);
        long one = RelayBlobLimits.MAX_FOLD_ACCOUNTED - probe.remaining();

        RelayBlobLimits.FoldBudget budget = new RelayBlobLimits.FoldBudget(one + one / 2);
        assertNotNull(CarriageBlockSnapshot.decode(blob, budget));
        RelayBlobTooLargeException e = assertThrows(RelayBlobTooLargeException.class,
                () -> CarriageBlockSnapshot.decode(blob, budget));
        assertTrue(e.wholeBuild(), "the second read is over what was left, so the build is spent");
    }

    @Test
    @DisplayName("more pending deltas than the relay allows refuses the build")
    void tooManyDeltas() throws Exception {
        String cells = CarriageBlockSnapshot.encode(LeaseSnapshotsTest.delta(null, 1, 1, 1));
        List<DeltaRec> deltas = new ArrayList<>();
        for (int seq = 1; seq <= RelayBlobLimits.MAX_DELTAS + 1; seq++) deltas.add(new DeltaRec(seq, cells));
        PoolLease lease = lease(CarriageBlockSnapshot.encode(LeaseSnapshotsTest.base(4, 3, 3)), deltas);
        RelayBlobTooLargeException e = assertThrows(RelayBlobTooLargeException.class, () -> LeaseSnapshots.fold(lease));
        assertTrue(e.wholeBuild());
    }

    @Test
    @DisplayName("an oversize base refuses the lease")
    void oversizeBaseRefusesLease() throws Exception {
        PoolLease lease = lease(byteArrayBomb(300 * 1024 * 1024), List.of());
        assertThrows(RelayBlobTooLargeException.class, () -> LeaseSnapshots.fold(lease));
    }

    @Test
    @DisplayName("each thing is reported once")
    void reportsOnce() {
        String key = "test:" + System.nanoTime();
        assertTrue(RelayBlobLimits.firstReport(key));
        assertFalse(RelayBlobLimits.firstReport(key));
    }
}
