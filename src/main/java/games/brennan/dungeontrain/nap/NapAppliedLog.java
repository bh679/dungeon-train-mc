package games.brennan.dungeontrain.nap;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.SealedBytes;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.zip.CRC32;

/**
 * Which naps may wake, in {@code naps-applied.bin}: the one nap each {@code <uuid>-<slot>} is allowed
 * to wake ({@link State#pending}), and the ids of naps that already woke ({@link State#woken}).
 *
 * <p>v3 layout: magic {@code DTNA}, version 3, a count and that many woken UUIDs (two longs each), a
 * count and that many pending entries (a UTF key, then a UUID), and an HMAC-SHA256 under the install
 * key ({@link SealedBytes}) over everything before it. A nap wakes only while it is the pending one
 * for its slot, so a list that is deleted, rebuilt or edited refuses naps rather than trusting them.</p>
 *
 * <p>A list that fails its seal (edited, truncated, sealed under another install key) is moved aside
 * to {@code naps-applied.bin.damaged-<millis>} and an empty one started: no pending nap wakes.</p>
 *
 * <p>Migration: while there is no v3 list (none yet, a v1/v2 CRC list, or the older
 * {@code naps-applied.txt}), the first read takes the old list's ids and {@code trustSince} as a
 * {@link Legacy} state and asks the store which waiting naps from before the upgrade to adopt as
 * pending ({@link NapStore}). Naps written by this version carry a marker and are never adopted, so
 * removing the list can never bring one of them back.</p>
 */
final class NapAppliedLog {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String FILE = "naps-applied.bin";
    static final String LEGACY_FILE = "naps-applied.txt";
    static final String DAMAGED_PREFIX = FILE + ".damaged-";
    static final String SEAL_SCOPE = "naps|applied";
    private static final int MAGIC = 0x44544E41; // "DTNA"
    private static final byte V1 = 1;
    private static final byte V2 = 2;
    private static final byte V3 = 3;
    /** Client (title screen) and integrated server share these files in one JVM. */
    private static final Object LOCK = new Object();

    /** What the list says: naps that already woke, and the one nap each slot may wake. */
    record State(Set<UUID> woken, Map<String, UUID> pending) {
        static final State EMPTY = new State(Set.of(), Map.of());

        State {
            woken = Set.copyOf(woken);
            pending = Map.copyOf(pending);
        }

        /** Is {@code napId} the nap {@code key} may wake? */
        boolean allows(String key, UUID napId) {
            return napId.equals(pending.get(key));
        }
    }

    /** A pre-v3 list: woken ids, and the earliest save time a nap may have to wake. */
    record Legacy(Set<UUID> ids, long trustSince) {
        static final Legacy EMPTY = new Legacy(Set.of(), 0L);

        boolean trusts(NapSnapshot nap) {
            return nap.savedAtEpochMs() >= trustSince;
        }
    }

    /** The list failed its check. */
    static final class Corrupt extends IOException {
        Corrupt(String message) {
            super(message);
        }
    }

    private final Path dir;
    private final LongSupplier clock;
    /** Given the old list, the pending naps from before the upgrade to carry over. */
    private final Function<Legacy, Map<String, UUID>> adopt;

    NapAppliedLog(Path dir, Function<Legacy, Map<String, UUID>> adopt) {
        this(dir, System::currentTimeMillis, adopt);
    }

    NapAppliedLog(Path dir, LongSupplier clock, Function<Legacy, Map<String, UUID>> adopt) {
        this.dir = dir;
        this.clock = clock;
        this.adopt = adopt;
    }

    /** The list, recovering a damaged file and migrating an older list if there is no v3 one. */
    State read() throws IOException {
        synchronized (LOCK) {
            Path file = dir.resolve(FILE);
            byte[] bytes = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            if (bytes != null && isV3(bytes)) {
                try {
                    return decode(bytes);
                } catch (Corrupt e) {
                    return recover(file, e);
                }
            }
            Legacy legacy;
            try {
                legacy = bytes == null ? Legacy.EMPTY : decodeLegacy(bytes);
            } catch (Corrupt e) {
                return recover(file, e);
            }
            return migrate(legacy);
        }
    }

    /** Makes {@code napId} the nap {@code key} may wake, replacing any earlier one. */
    void register(String key, UUID napId) throws IOException {
        synchronized (LOCK) {
            State state = read();
            if (state.allows(key, napId)) return;
            Map<String, UUID> pending = new HashMap<>(state.pending());
            pending.put(key, napId);
            write(new State(state.woken(), pending));
        }
    }

    /** Records {@code napId} as woken; it is no longer {@code key}'s pending nap. */
    void markWoken(String key, UUID napId) throws IOException {
        synchronized (LOCK) {
            State state = read();
            if (state.woken().contains(napId) && !state.allows(key, napId)) return;
            Set<UUID> woken = new HashSet<>(state.woken());
            woken.add(napId);
            Map<String, UUID> pending = new HashMap<>(state.pending());
            pending.remove(key, napId);
            write(new State(woken, pending));
        }
    }

    private State recover(Path file, Corrupt e) throws IOException {
        long now = clock.getAsLong();
        Path aside = dir.resolve(DAMAGED_PREFIX + now);
        Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        write(State.EMPTY);
        LOGGER.warn("[DungeonTrain] {}; moved it to {} and started a new list. Waiting naps won't wake.",
            e.getMessage(), aside);
        return State.EMPTY;
    }

    /** No v3 list yet: carry the old ids over, adopt the store's waiting pre-upgrade naps, drop the text list. */
    private State migrate(Legacy fromBinary) throws IOException {
        Legacy legacy = mergeTextList(fromBinary);
        Map<String, UUID> pending = adopt.apply(legacy);
        State out = new State(legacy.ids(), pending == null ? Map.of() : pending);
        write(out);
        Files.deleteIfExists(dir.resolve(LEGACY_FILE));
        if (!legacy.ids().isEmpty() || !out.pending().isEmpty()) {
            LOGGER.info("[DungeonTrain] Nap list moved to v3: {} woken id(s), {} waiting nap(s) carried over",
                legacy.ids().size(), out.pending().size());
        }
        return out;
    }

    private Legacy mergeTextList(Legacy state) throws IOException {
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
        return new Legacy(merged, state.trustSince());
    }

    private static boolean isV3(byte[] bytes) {
        return bytes.length >= 5 && ByteBuffer.wrap(bytes).getInt() == MAGIC && bytes[4] == V3;
    }

    static State decode(byte[] bytes) throws Corrupt {
        int body = bytes.length - SealedBytes.MAC_BYTES;
        if (body < 5 + 4 + 4) throw new Corrupt("nap list too short");
        byte[] seal = Arrays.copyOfRange(bytes, body, bytes.length);
        if (!SealedBytes.matches(SEAL_SCOPE, bytes, 0, body, seal)) throw new Corrupt("nap list fails its seal");
        try {
            ByteBuffer buf = ByteBuffer.wrap(bytes, 0, body);
            if (buf.getInt() != MAGIC || buf.get() != V3) throw new Corrupt("nap list has no v3 header");
            int wokenCount = buf.getInt();
            if (wokenCount < 0 || 16L * wokenCount > buf.remaining()) throw new Corrupt("nap list has a bad count");
            Set<UUID> woken = new HashSet<>();
            for (int i = 0; i < wokenCount; i++) woken.add(new UUID(buf.getLong(), buf.getLong()));
            int pendingCount = buf.getInt();
            if (pendingCount < 0 || 18L * pendingCount > buf.remaining()) throw new Corrupt("nap list has a bad count");
            Map<String, UUID> pending = new HashMap<>();
            for (int i = 0; i < pendingCount; i++) {
                byte[] key = new byte[Short.toUnsignedInt(buf.getShort())];
                buf.get(key);
                pending.put(new String(key, StandardCharsets.UTF_8), new UUID(buf.getLong(), buf.getLong()));
            }
            if (buf.hasRemaining()) throw new Corrupt("nap list has the wrong length");
            return new State(woken, pending);
        } catch (BufferUnderflowException e) {
            throw new Corrupt("nap list too short");
        }
    }

    static byte[] encode(State state) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(64 + 16 * state.woken().size() + 64 * state.pending().size());
            DataOutputStream out = new DataOutputStream(raw);
            out.writeInt(MAGIC);
            out.writeByte(V3);
            out.writeInt(state.woken().size());
            for (UUID id : state.woken()) writeUuid(out, id);
            out.writeInt(state.pending().size());
            for (Map.Entry<String, UUID> e : state.pending().entrySet()) {
                byte[] key = e.getKey().getBytes(StandardCharsets.UTF_8);
                out.writeShort(key.length);
                out.write(key);
                writeUuid(out, e.getValue());
            }
            out.flush();
            byte[] body = raw.toByteArray();
            byte[] seal = SealedBytes.mac(SEAL_SCOPE, body);
            if (seal.length != SealedBytes.MAC_BYTES) throw new IllegalStateException("nap list can't be sealed");
            out.write(seal);
            out.flush();
            return raw.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory write failed", e);
        }
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    /**
     * A v1 or v2 list (before v3): magic, version, a {@code trustSince} time (v2 only), a count, that
     * many UUIDs, and a CRC32 of everything before it.
     */
    static Legacy decodeLegacy(byte[] bytes) throws Corrupt {
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
        return new Legacy(ids, trustSince);
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
