package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.builder.relay.SubmitNote;
import games.brennan.dungeontrain.editor.SubmitHints;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wire-format round trips for the submit / withdraw request.
 *
 * <p>The note is the newest field and the one a reviewer reads, so a codec that dropped or
 * truncated it would submit the build fine and lose exactly the thing the author was asked for.</p>
 */
final class BuilderProfileActionPacketTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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
    @DisplayName("the hints answer carries both lists of found blocks")
    void hintsRoundTrip() {
        SubmitHints.Found repeater = new SubmitHints.Found(Blocks.REPEATER, 3, SubmitHints.Kind.REDSTONE, "", 3);
        SubmitHints.Found chest = new SubmitHints.Found(Blocks.CHEST, 1, SubmitHints.Kind.TABLE,
                "minecraft:chests/simple_dungeon", 12.5);
        for (SubmitHints.Hints h : new SubmitHints.Hints[] {SubmitHints.Hints.NONE,
                new SubmitHints.Hints(List.of(repeater), List.of()),
                new SubmitHints.Hints(List.of(repeater), List.of(chest))}) {
            BuilderSubmitHintsPacket original = new BuilderSubmitHintsPacket(99, h, h.hasLoot());
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                BuilderSubmitHintsPacket.STREAM_CODEC.encode(buf, original);
                assertEquals(original, BuilderSubmitHintsPacket.STREAM_CODEC.decode(buf));
            } finally {
                buf.release();
            }
        }
    }

    @Test
    @DisplayName("a hints request names whose build and which pool")
    void hintsRequestRoundTrip() {
        for (BuilderSubmitHintsRequestPacket original : new BuilderSubmitHintsRequestPacket[] {
                new BuilderSubmitHintsRequestPacket(7),
                new BuilderSubmitHintsRequestPacket(8, "380df991-f603-344c-a090-369bad2a924a", true)}) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                BuilderSubmitHintsRequestPacket.STREAM_CODEC.encode(buf, original);
                assertEquals(original, BuilderSubmitHintsRequestPacket.STREAM_CODEC.decode(buf));
            } finally {
                buf.release();
            }
        }
    }

    @Test
    @DisplayName("an answers edit carries the build, its owner and every answer")
    void noteEditRoundTrip() {
        BuilderNoteEditPacket original = new BuilderNoteEditPacket(12, "380df991-f603-344c-a090-369bad2a924a",
                false, new SubmitNote("lever first", "", "near the engine"));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BuilderNoteEditPacket.STREAM_CODEC.encode(buf, original);
            assertEquals(original, BuilderNoteEditPacket.STREAM_CODEC.decode(buf));
        } finally {
            buf.release();
        }
        assertEquals(SubmitNote.EMPTY, new BuilderNoteEditPacket(1, null, false, null).note());
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
