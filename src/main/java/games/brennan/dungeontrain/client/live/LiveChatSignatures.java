package games.brennan.dungeontrain.client.live;

import net.minecraft.network.chat.MessageSignature;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A made-up {@link MessageSignature} for each live-chat line this client printed, keyed by the relay's seq,
 * so a line the relay later reports deleted (a Twitch mod removed it) can go through vanilla's own
 * {@code ChatComponent.deleteMessage} — the line turns into vanilla's grey "deleted" marker, the same
 * as a server-deleted chat message. The signature is only ever an identity for that lookup: it is never
 * sent anywhere and never verified. A per-session salt keeps it from colliding with real signed chat.
 *
 * <p>Client thread only. Holds the newest {@link #MAX} lines (the relay's ring holds 200).</p>
 */
final class LiveChatSignatures {

    static final int MAX = 256;
    private static final int BYTES = 256; // MessageSignature insists on exactly this

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
        ByteBuffer b = ByteBuffer.allocate(BYTES);
        while (b.remaining() >= 16) b.putLong(salt).putLong(seq);
        MessageSignature sig = new MessageSignature(b.array());
        bySeq.put(seq, sig);
        return sig;
    }

    /** The signature line {@code seq} was printed with, forgotten here; null when it was never printed (or too long ago). */
    @Nullable
    MessageSignature take(long seq) {
        return bySeq.remove(seq);
    }

    /** A new chat session (connect / disconnect): the relay's seqs may belong to another ring now. */
    void clear() {
        bySeq.clear();
    }

    int size() {
        return bySeq.size();
    }
}
