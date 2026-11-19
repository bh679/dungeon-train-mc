package games.brennan.dungeontrain.nap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.UUID;
import java.util.zip.CRC32;

/** Files as earlier versions wrote them, for the nap list's migration tests. */
final class NapTestFiles {

    private NapTestFiles() {}

    static NapSnapshot napAt(String levelId, long savedAt) {
        return new NapSnapshot(UUID.randomUUID(), savedAt, "0.1840.0", levelId, new CompoundTag(),
            0, 1, "", 1, 1, false, "hard");
    }

    /** A nap file with no {@link NapStore#MARK}, as a version before the v3 list saved it. */
    static void writeUnmarked(NapStore store, UUID player, String slot, NapSnapshot nap) throws IOException {
        Files.createDirectories(store.fileFor(player, slot).getParent());
        NbtIo.writeCompressed(nap.toTag(), store.fileFor(player, slot));
    }

    /** A v1 or v2 CRC32 list ({@code trustSince} ignored for v1). */
    static byte[] legacyList(byte version, long trustSince, UUID... ids) {
        int header = version == 2 ? 5 + 8 + 4 : 5 + 4;
        ByteBuffer buf = ByteBuffer.allocate(header + 16 * ids.length + 8);
        buf.putInt(0x44544E41).put(version);
        if (version == 2) buf.putLong(trustSince);
        buf.putInt(ids.length);
        for (UUID id : ids) buf.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        CRC32 crc = new CRC32();
        crc.update(buf.array(), 0, buf.position());
        buf.putLong(crc.getValue());
        return buf.array();
    }
}
