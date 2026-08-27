package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wire-format round trips for the download request and its answer.
 *
 * <p>The answer's three name fields are what the screen opens the build with, so a codec that
 * shuffled or truncated them would install the right template and then open the wrong one — or
 * nothing. The outcome enum is written ordinally, which is fine on one jar and is exactly why the
 * round trip is pinned rather than assumed.</p>
 */
final class BuilderProfileDownloadPacketTest {

    @Test
    @DisplayName("the request carries the relay id")
    void requestRoundTrip() {
        BuilderProfileDownloadPacket original = new BuilderProfileDownloadPacket(4271);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        BuilderProfileDownloadPacket.STREAM_CODEC.encode(buf, original);
        assertEquals(original, BuilderProfileDownloadPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("the answer preserves the outcome and what was installed")
    void resultRoundTrip() {
        BuilderProfileDownloadResultPacket original = new BuilderProfileDownloadResultPacket(
                BuilderRelayDownload.Outcome.INSTALLED, BuilderPhotoPaths.Kind.PART.id(),
                "brass_door", "door");
        assertEquals(original, roundTrip(original));
    }

    @Test
    @DisplayName("an outcome that installed nothing round-trips with empty names")
    void refusalRoundTrip() {
        BuilderProfileDownloadResultPacket original = new BuilderProfileDownloadResultPacket(
                BuilderRelayDownload.Outcome.ALREADY_HERE, "", "", "");
        assertEquals(original, roundTrip(original));
    }

    @Test
    @DisplayName("every outcome survives the wire")
    void everyOutcomeRoundTrips() {
        for (BuilderRelayDownload.Outcome outcome : BuilderRelayDownload.Outcome.values()) {
            BuilderProfileDownloadResultPacket original =
                    new BuilderProfileDownloadResultPacket(outcome, "carriage", "brick_cabin", "");
            assertEquals(outcome, roundTrip(original).outcome());
        }
    }

    private static BuilderProfileDownloadResultPacket roundTrip(BuilderProfileDownloadResultPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        BuilderProfileDownloadResultPacket.STREAM_CODEC.encode(buf, packet);
        return BuilderProfileDownloadResultPacket.STREAM_CODEC.decode(buf);
    }
}
