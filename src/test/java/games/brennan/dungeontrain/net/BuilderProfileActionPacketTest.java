package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wire-format round trips for the submit / withdraw request.
 *
 * <p>The note is the newest field and the one a reviewer reads, so a codec that dropped or
 * truncated it would submit the build fine and lose exactly the thing the author was asked for.</p>
 */
final class BuilderProfileActionPacketTest {

    @Test
    @DisplayName("a submit carries its note to the reviewer across the wire")
    void submitWithNoteRoundTrip() {
        BuilderProfileActionPacket original = new BuilderProfileActionPacket(4271, true,
                "Pull the lever by the door first.\nThe chest loot is meant to be there.");
        assertEquals(original, roundTrip(original));
    }

    @Test
    @DisplayName("the two-arg form is a request with nothing to tell the reviewer")
    void twoArgFormHasEmptyNote() {
        BuilderProfileActionPacket withdraw = new BuilderProfileActionPacket(4271, false);
        assertEquals("", withdraw.note());
        assertEquals(withdraw, roundTrip(withdraw));
    }

    @Test
    @DisplayName("a null note reads as an empty one rather than a crash on encode")
    void nullNoteBecomesEmpty() {
        BuilderProfileActionPacket packet = new BuilderProfileActionPacket(4271, true, null);
        assertEquals("", packet.note());
        assertEquals(packet, roundTrip(packet));
    }

    @Test
    @DisplayName("a note at the cap survives intact")
    void noteAtCapRoundTrip() {
        String note = "x".repeat(BuilderProfileActionPacket.NOTE_MAX);
        BuilderProfileActionPacket original = new BuilderProfileActionPacket(1, true, note);
        assertEquals(original, roundTrip(original));
    }

    private static BuilderProfileActionPacket roundTrip(BuilderProfileActionPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BuilderProfileActionPacket.STREAM_CODEC.encode(buf, packet);
            return BuilderProfileActionPacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }
}
