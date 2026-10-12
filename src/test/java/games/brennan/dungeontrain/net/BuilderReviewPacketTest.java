package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reviewer's verdict on the wire. The one rule worth pinning: the picture rides only with an accept,
 * because it exists for the announcement and only an accept makes one — a megabyte of PNG on a decline
 * would be sent for nothing.
 */
final class BuilderReviewPacketTest {

    private static final String OWNER = "22222222-2222-4222-8222-222222222222";

    @Test
    @DisplayName("an accept carries its picture and comment round the wire")
    void acceptRoundTrip() {
        BuilderReviewPacket original = new BuilderReviewPacket(41, OWNER, "Ada", "brick_cabin", "carriage", "",
                false, BuilderReviewState.ACCEPTED, "Lovely roofline.", new byte[] {1, 2, 3}, "", "");
        BuilderReviewPacket back = roundTrip(original);
        assertEquals(original, back);
        assertEquals(3, back.render().length);
        assertEquals("Lovely roofline.", back.comment());
    }

    @Test
    @DisplayName("a decline drops the picture; feedback keeps it, since the channel hears about feedback")
    void renderOnlyForAnnouncedVerdicts() {
        BuilderReviewPacket declined = new BuilderReviewPacket(41, OWNER, "Ada", "brick_cabin", "carriage", "",
                true, BuilderReviewState.DECLINED, "Needs a door.", new byte[] {9, 9}, "0.1130.0", "gte");
        assertEquals("", declined.version(), "a rule rides only with a resubmit");
        assertEquals(0, declined.render().length);
        assertEquals(declined, roundTrip(declined));
        BuilderReviewPacket feedback = new BuilderReviewPacket(41, OWNER, "Ada", "brick_cabin", "carriage", "",
                true, BuilderReviewState.FEEDBACK, "Needs a door.", new byte[] {9, 9}, "", "");
        assertEquals(2, feedback.render().length);
        assertEquals(feedback, roundTrip(feedback));
    }

    @Test
    @DisplayName("an unknown verdict reads as none, so a stale client cannot invent a state")
    void unknownReview() {
        BuilderReviewPacket p = new BuilderReviewPacket(41, OWNER, "Ada", "b", "carriage", "", false,
                "approved-ish", "", null, "", "");
        assertEquals(BuilderReviewState.NONE, p.review());
        assertEquals(0, p.render().length);
    }

    @Test
    @DisplayName("a resubmit carries its version rule; an unknown op reads as or-above")
    void resubmitRule() {
        BuilderReviewPacket p = new BuilderReviewPacket(41, OWNER, "Ada", "b", "carriage", "", false,
                BuilderReviewState.RESUBMIT, "Fixed in the next one.", new byte[] {1}, " 0.1130.0 ", "sideways");
        assertEquals("0.1130.0", p.version());
        assertEquals(BuilderReviewState.OP_GTE, p.versionOp());
        assertEquals(1, p.render().length, "a resubmit is announced, so its picture rides along");
        assertEquals(p, roundTrip(p));
    }

    private static BuilderReviewPacket roundTrip(BuilderReviewPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BuilderReviewPacket.STREAM_CODEC.encode(buf, packet);
            return BuilderReviewPacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }
}
