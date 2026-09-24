package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.builder.relay.SubmitNote;
import games.brennan.dungeontrain.editor.SubmitHints;
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
        BuilderProfileActionPacket original = new BuilderProfileActionPacket(4271, true, new SubmitNote(
                "Pull the lever by the door first.", "The chest loot is meant to be there.",
                "Best near the engine.\nThanks!"));
        assertEquals(original, roundTrip(original));
    }

    @Test
    @DisplayName("the two-arg form is a request with nothing to tell the reviewer")
    void twoArgFormHasEmptyNote() {
        BuilderProfileActionPacket withdraw = new BuilderProfileActionPacket(4271, false);
        assertEquals(SubmitNote.EMPTY, withdraw.note());
        assertEquals(withdraw, roundTrip(withdraw));
    }

    @Test
    @DisplayName("a null note reads as an empty one rather than a crash on encode")
    void nullNoteBecomesEmpty() {
        BuilderProfileActionPacket packet = new BuilderProfileActionPacket(4271, true, null);
        assertEquals(SubmitNote.EMPTY, packet.note());
        assertEquals(packet, roundTrip(packet));
    }

    @Test
    @DisplayName("a note at the cap survives intact")
    void noteAtCapRoundTrip() {
        String full = "x".repeat(BuilderProfileActionPacket.NOTE_MAX);
        BuilderProfileActionPacket original = new BuilderProfileActionPacket(1, true,
                new SubmitNote(full, full, full));
        assertEquals(original, roundTrip(original));
    }

    @Test
    @DisplayName("null fields in a note read as empty ones")
    void nullFieldsBecomeEmpty() {
        SubmitNote note = new SubmitNote(null, "loot", null);
        assertEquals(new SubmitNote("", "loot", ""), note);
        assertEquals(note, roundTrip(new BuilderProfileActionPacket(3, true, note)).note());
    }

    @Test
    @DisplayName("the hints answer carries both flags")
    void hintsRoundTrip() {
        for (SubmitHints.Hints h : new SubmitHints.Hints[] {SubmitHints.Hints.NONE,
                new SubmitHints.Hints(true, false), new SubmitHints.Hints(false, true), new SubmitHints.Hints(true, true)}) {
            BuilderSubmitHintsPacket original = new BuilderSubmitHintsPacket(99, h);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                BuilderSubmitHintsPacket.STREAM_CODEC.encode(buf, original);
                assertEquals(original, BuilderSubmitHintsPacket.STREAM_CODEC.decode(buf));
            } finally {
                buf.release();
            }
        }
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
