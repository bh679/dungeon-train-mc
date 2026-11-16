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
import java.util.zip.CRC32;

/**
 * The ids of naps that already woke, in {@code naps-applied.bin}: magic {@code DTNA}, a version byte,
 * a count, that many UUIDs as two longs each, and a CRC32 of everything before it. Binary so it is not
 * edited by hand; the checksum catches a truncated or edited file, which then reads as
 * {@link Corrupt} and the store fails closed (no nap wakes, nothing is overwritten).
 *
 * <p>An older {@code naps-applied.txt} is merged in on first read and removed once the binary file
 * holding its ids is written.</p>
 */
final class NapAppliedLog {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String FILE = "naps-applied.bin";
    static final String LEGACY_FILE = "naps-applied.txt";
    private static final int MAGIC = 0x44544E41; // "DTNA"
    private static final byte VERSION = 1;
    private static final int HEADER = 4 + 1 + 4;

    /** The applied file failed its check: no nap may wake and it must not be overwritten. */
    static final class Corrupt extends IOException {
        Corrupt(String message) {
            super(message);
        }
    }

    private final Path dir;

    NapAppliedLog(Path dir) {
        this.dir = dir;
    }

    /** Every recorded id, migrating the old text list if one is there. */
    Set<UUID> read() throws IOException {
        Set<UUID> ids = readBinary();
        Path legacy = dir.resolve(LEGACY_FILE);
        if (!Files.isRegularFile(legacy)) return ids;
        Set<UUID> merged = new HashSet<>(ids);
        for (String line : Files.readAllLines(legacy, StandardCharsets.UTF_8)) {
            String id = line.trim();
            if (id.isEmpty()) continue;
            try {
                merged.add(UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("[DungeonTrain] Skipping bad line in {}: {}", legacy, id);
            }
        }
        write(merged);
        Files.deleteIfExists(legacy);
        LOGGER.info("[DungeonTrain] Moved {} applied nap id(s) from {} to {}", merged.size(), LEGACY_FILE, FILE);
        return merged;
    }

    /** Records {@code id}; refuses (throws {@link Corrupt}) when the file fails its check. */
    void add(UUID id) throws IOException {
        Set<UUID> ids = new HashSet<>(read());
        if (ids.add(id)) write(ids);
    }

    private Set<UUID> readBinary() throws IOException {
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) return Set.of();
        return decode(Files.readAllBytes(file));
    }

    static Set<UUID> decode(byte[] bytes) throws Corrupt {
        if (bytes.length < HEADER + 8) throw new Corrupt("applied nap list too short");
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        if (buf.getInt() != MAGIC) throw new Corrupt("applied nap list has no header");
        if (buf.get() != VERSION) throw new Corrupt("applied nap list has an unknown version");
        int count = buf.getInt();
        if (count < 0 || (long) HEADER + 16L * count + 8 != bytes.length) {
            throw new Corrupt("applied nap list has the wrong length");
        }
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < count; i++) ids.add(new UUID(buf.getLong(), buf.getLong()));
        long stored = buf.getLong();
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 8);
        if (crc.getValue() != stored) throw new Corrupt("applied nap list fails its checksum");
        return ids;
    }

    static byte[] encode(Set<UUID> ids) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(HEADER + 16 * ids.size() + 8);
            DataOutputStream out = new DataOutputStream(raw);
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeInt(ids.size());
            for (UUID id : ids) {
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
    private void write(Set<UUID> ids) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(FILE);
        Path tmp = dir.resolve(FILE + ".tmp");
        Files.write(tmp, encode(ids));
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
