package games.brennan.dungeontrain.client.live;

import net.minecraft.network.chat.MessageSignature;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A made-up {@link MessageSignature} for each live-chat line this client printed, keyed by the relay's seq,
 * so a line the relay later reports deleted (a Twitch mod removed it) can go through vanilla's own
 * {@code ChatComponent.deleteMessage} — the line turns into vanilla's grey "deleted" marker, the same
 * as a server-deleted chat message. The signature is only ever an identity for that lookup: it is never
 * sent anywhere and never verified. A per-session salt keeps it from colliding with real signed chat.
 *
 * <p>A line's translation printed under it (LiveChatTranslations) is a second part of the same seq, so the
 * two go together when the line is deleted.</p>
 *
 * <p>Client thread only. Holds the newest {@link #MAX} entries (the relay's ring holds 200).</p>
 */
final class LiveChatSignatures {

    static final int MAX = 256;
    private static final int BYTES = 256; // MessageSignature insists on exactly this
    /** Lines printed per seq: the line and, under it, its translation. */
    private static final int PARTS = 2;

    private final long salt;
    private final Map<Long, MessageSignature> bySeq = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, MessageSignature> eldest) {
            return size() > MAX;
        }
    };

    LiveChatSignatures() {
        this(new SecureRandom().nextLong());
    }

    LiveChatSignatures(long salt) {
        this.salt = salt;
    }

    /** The signature to print line {@code seq} with; remembered until taken or pushed out. */
    MessageSignature sign(long seq) {
        return signPart(seq, 0);
    }

    /**
     * The signature for an extra line printed for {@code seq} — its translation under it ({@code part} 1) —
     * so a deletion of the line takes that one too ({@link #takeAll}). Part 0 is the line itself.
     */
    MessageSignature signPart(long seq, int part) {
        ByteBuffer b = ByteBuffer.allocate(BYTES);
        while (b.remaining() >= 24) b.putLong(salt).putLong(seq).putLong(part);
        MessageSignature sig = new MessageSignature(b.array());
        bySeq.put(key(seq, part), sig);
        return sig;
    }

    /** The signature line {@code seq} was printed with, forgotten here; null when it was never printed (or too long ago). */
    @Nullable
    MessageSignature take(long seq) {
        return bySeq.remove(key(seq, 0));
    }

    /** Every signature printed for {@code seq} (the line and any part under it), all forgotten here. */
    List<MessageSignature> takeAll(long seq) {
        List<MessageSignature> out = new ArrayList<>();
        for (int part = 0; part < PARTS; part++) {
            MessageSignature sig = bySeq.remove(key(seq, part));
            if (sig != null) out.add(sig);
        }
        return out;
    }

    private static long key(long seq, int part) {
        return seq * PARTS + part;
    }

    /** A new chat session (connect / disconnect): the relay's seqs may belong to another ring now. */
    void clear() {
        bySeq.clear();
    }

    int size() {
        return bySeq.size();
    }
}
