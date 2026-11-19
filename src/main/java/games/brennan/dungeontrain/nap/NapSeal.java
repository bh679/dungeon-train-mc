package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.data.SealedBytes;
import games.brennan.dungeontrain.data.SealedJson;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * The seal on a nap file, so a hand edit is noticed when it next wakes.
 *
 * <p>A sealed file (v2) is {@code {dtnap: 2, body: <the nap tag as raw NBT bytes>, seal: <HMAC>}},
 * gzip as before. The seal is {@link SealedBytes} under the install key over {@code body}, scoped to
 * the player and slot the file is named for, so an edit, or a file moved to another slot or player,
 * no longer matches. Sealing the stored bytes rather than a re-written tag keeps it independent of
 * NBT's unordered compound keys.</p>
 *
 * <p>A v1 file (the bare nap tag) has no seal. {@link NapStore} seals the waiting ones in place the
 * first time it meets them and leaves {@link #MARKER} beside them; after that a v1 file is one whose
 * seal was stripped. Same honest limit as the key: it stops hand edits, not someone who reads this.</p>
 */
final class NapSeal {

    static final String TAG_FORMAT = "dtnap";
    static final String TAG_BODY = "body";
    static final String TAG_SEAL = "seal";
    static final int FORMAT = 2;
    /** Beside the nap files once this install seals them: from then on an unsealed file is not trusted. */
    static final String MARKER = ".sealed";

    /** How a nap file's contents stand. */
    enum Verdict {
        /** The seal matches. */
        SEALED,
        /** The seal does not match: the body was changed, or the file was moved to another slot or player. */
        TAMPERED,
        /** No seal at all (a v1 file); whether it is trusted depends on {@link #MARKER}. */
        UNSEALED
    }

    /** The nap tag a file holds, and how it stands. */
    record Opened(CompoundTag body, Verdict verdict) {}

    private NapSeal() {}

    static String scope(UUID player, String slot) {
        return SealedJson.scope("nap", player, slot);
    }

    /** The file root for {@code body} sealed under {@code scope}. */
    static CompoundTag wrap(CompoundTag body, String scope) throws IOException {
        byte[] bytes = toBytes(body);
        CompoundTag root = new CompoundTag();
        root.putInt(TAG_FORMAT, FORMAT);
        root.putByteArray(TAG_BODY, bytes);
        root.putByteArray(TAG_SEAL, SealedBytes.mac(scope, bytes));
        return root;
    }

    /** Is {@code root} a sealed (v2) file? */
    static boolean isSealedFormat(CompoundTag root) {
        return root.contains(TAG_FORMAT);
    }

    /**
     * The nap tag in {@code root} and its verdict; null when a v2 file's body doesn't read as NBT (no
     * nap in it). A v1 root is returned as itself, {@link Verdict#UNSEALED}.
     */
    static Opened open(CompoundTag root, String scope) {
        if (!isSealedFormat(root)) return new Opened(root, Verdict.UNSEALED);
        byte[] bytes = root.getByteArray(TAG_BODY);
        CompoundTag body;
        try {
            body = fromBytes(bytes);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        boolean matches = root.getInt(TAG_FORMAT) == FORMAT
            && SealedBytes.matches(scope, bytes, 0, bytes.length, root.getByteArray(TAG_SEAL));
        return new Opened(body, matches ? Verdict.SEALED : Verdict.TAMPERED);
    }

    static byte[] toBytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            NbtIo.write(tag, out);
        }
        return raw.toByteArray();
    }

    static CompoundTag fromBytes(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return NbtIo.read(in, NbtAccounter.unlimitedHeap());
        }
    }
}
