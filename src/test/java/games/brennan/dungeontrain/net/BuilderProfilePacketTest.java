package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wire-format round trips for the profile the My Builds screen draws.
 *
 * <p>Every field here is read positionally out of one flat buffer, so a field added in the writer and
 * missed in the reader does not fail — it slides every later field along by one and the screen shows a
 * stage where a review state should be. That is the failure this pins.</p>
 */
final class BuilderProfilePacketTest {

    /** Whose profile these replies are about — the owner fields ride along on every one. */
    private static final String MINE = "11111111-1111-4111-8111-111111111111";

    @Test
    @DisplayName("a build's submission state survives the wire alongside everything else")
    void entryRoundTrip() {
        BuilderProfilePacket original = new BuilderProfilePacket(BuilderProfilePacket.Status.OK, List.of(
                new BuilderProfilePacket.Entry(41, "carriage", "", "brick_cabin", true,
                        "approved", BuilderReviewState.SUBMITTED, "stone", 12),
                new BuilderProfilePacket.Entry(42, "portal_room", "", "library", false,
                        "approved", BuilderReviewState.NONE, "", 0)),
                MINE, "Brennan", true);
        assertEquals(original, roundTrip(original));
    }

    @Test
    @DisplayName("a status with no builds still round-trips its reason")
    void statusRoundTrip() {
        for (BuilderProfilePacket.Status status : BuilderProfilePacket.Status.values()) {
            BuilderProfilePacket original = BuilderProfilePacket.of(status, MINE, "Brennan", true);
            assertEquals(original, roundTrip(original), "the screen tells these six apart: " + status);
        }
    }

    @Test
    @DisplayName("a relay too old to send a review state is read as never-submitted, not as blank")
    void relayWithoutReview() {
        // What SharedCarriageClient.listMine produces from a row with no `review` field: the empty
        // string. It must not reach the screen, which looks up lang keys by this value.
        SharedCarriageClient.ProfileBuild row = new SharedCarriageClient.ProfileBuild(
                7, "carriage", "", "brick_cabin", "published", "builder", "stone", "approved", "",
                7, 5, 5, 3, 1L);
        BuilderProfilePacket packet = BuilderProfilePacket.of(List.of(row), MINE, "Brennan", true);
        assertEquals(BuilderReviewState.NONE, packet.builds().get(0).review());
        assertEquals(packet, roundTrip(packet));
    }

    private static BuilderProfilePacket roundTrip(BuilderProfilePacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        BuilderProfilePacket.STREAM_CODEC.encode(buf, packet);
        return BuilderProfilePacket.STREAM_CODEC.decode(buf);
    }
}
