package games.brennan.dungeontrain.nap;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.zip.CRC32;

/**
 * The ids of naps that already woke, in {@code naps-applied.bin}: magic {@code DTNA}, a version byte,
 * a {@code trustSince} time (v2), a count, that many UUIDs as two longs each, and a CRC32 of everything
 * before it. Binary so it is not edited by hand.
 *
 * <p>A file that fails its check (edited, truncated) is moved aside to
 * {@code naps-applied.bin.damaged-<millis>} and a fresh list is started whose {@code trustSince} is
 * that moment: naps saved before it are refused (a copy taken before the damage can't wake), naps
 * saved after it wake as usual. A v1 file reads as trusting everything ({@code trustSince} 0).</p>
 *
 * <p>An older {@code naps-applied.txt} is merged in on first read and removed once the binary file
 * holding its ids is written.</p>
 */
final class NapAppliedLog {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String FILE = "naps-applied.bin";
    static final String LEGACY_FILE = "naps-applied.txt";
    static final String DAMAGED_PREFIX = FILE + ".damaged-";
    private static final int MAGIC = 0x44544E41; // "DTNA"
    private static final byte V1 = 1;
    private static final byte V2 = 2;

    /** What the list says: woken ids, and the earliest save time a nap may have to wake. */
    record State(Set<UUID> ids, long trustSince) {
        static final State EMPTY = new State(Set.of(), 0L);

        boolean trusts(NapSnapshot nap) {
            return nap.savedAtEpochMs() >= trustSince;
        }
    }

    /** The applied file failed its check. */
    static final class Corrupt extends IOException {
        Corrupt(String message) {
            super(message);
        }
    }

    private final Path dir;
    private final LongSupplier clock;

    NapAppliedLog(Path dir) {
        this(dir, System::currentTimeMillis);
    }

    NapAppliedLog(Path dir, LongSupplier clock) {
        this.dir = dir;
        this.clock = clock;
    }

    /** The list, recovering a damaged file and migrating the old text list if one is there. */
    State read() throws IOException {
        State state = readOrRecover();
        Path legacy = dir.resolve(LEGACY_FILE);
        if (!Files.isRegularFile(legacy)) return state;
        Set<UUID> merged = new HashSet<>(state.ids());
        for (String line : Files.readAllLines(legacy, StandardCharsets.UTF_8)) {
            String id = line.trim();
            if (id.isEmpty()) continue;
            try {
                merged.add(UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("[DungeonTrain] Skipping bad line in {}: {}", legacy, id);
            }
        }
        State out = new State(merged, state.trustSince());
        write(out);
        Files.deleteIfExists(legacy);
        LOGGER.info("[DungeonTrain] Moved {} applied nap id(s) from {} to {}", merged.size(), LEGACY_FILE, FILE);
        return out;
    }

    /** Records {@code id}. */
    void add(UUID id) throws IOException {
        State state = read();
        if (state.ids().contains(id)) return;
        Set<UUID> ids = new HashSet<>(state.ids());
        ids.add(id);
        write(new State(ids, state.trustSince()));
    }

    private State readOrRecover() throws IOException {
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) return State.EMPTY;
        try {
            return decode(Files.readAllBytes(file));
        } catch (Corrupt e) {
            long now = clock.getAsLong();
            Path aside = dir.resolve(DAMAGED_PREFIX + now);
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            State fresh = new State(Set.of(), now);
            write(fresh);
            LOGGER.warn("[DungeonTrain] {}; moved it to {} and started a new list. Naps saved before now won't wake.",
                e.getMessage(), aside);
            return fresh;
        }
    }

    static State decode(byte[] bytes) throws Corrupt {
        if (bytes.length < 5 + 4 + 8) throw new Corrupt("applied nap list too short");
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        if (buf.getInt() != MAGIC) throw new Corrupt("applied nap list has no header");
        byte version = buf.get();
        if (version != V1 && version != V2) throw new Corrupt("applied nap list has an unknown version");
        int header = version == V2 ? 5 + 8 + 4 : 5 + 4;
        if (bytes.length < header + 8) throw new Corrupt("applied nap list too short");
        long trustSince = version == V2 ? buf.getLong() : 0L;
        int count = buf.getInt();
        if (count < 0 || (long) header + 16L * count + 8 != bytes.length) {
            throw new Corrupt("applied nap list has the wrong length");
        }
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < count; i++) ids.add(new UUID(buf.getLong(), buf.getLong()));
        long stored = buf.getLong();
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 8);
        if (crc.getValue() != stored) throw new Corrupt("applied nap list fails its checksum");
        return new State(ids, trustSince);
    }

    static byte[] encode(State state) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(17 + 16 * state.ids().size() + 8);
            DataOutputStream out = new DataOutputStream(raw);
            out.writeInt(MAGIC);
            out.writeByte(V2);
            out.writeLong(state.trustSince());
            out.writeInt(state.ids().size());
            for (UUID id : state.ids()) {
                out.writeLong(id.getMostSignificantBits());
                out.writeLong(id.getLeastSignificantBits());
            }
            out.flush();
            CRC32 crc = new CRC32();
            crc.update(raw.toByteArray());
            out.writeLong(crc.getValue());
            out.flush();
            return raw.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory write failed", e);
        }
    }

    /** Temp file then atomic move, so a crash never leaves half a list. */
    private void write(State state) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(FILE);
        Path tmp = dir.resolve(FILE + ".tmp");
        Files.write(tmp, encode(state));
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
