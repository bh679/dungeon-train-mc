package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import games.brennan.dungeontrain.builder.relay.BuilderRelayInstall;
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
    @DisplayName("the request carries the relay id, and a first press asks for no resolution")
    void requestRoundTrip() {
        BuilderProfileDownloadPacket original = new BuilderProfileDownloadPacket(4271);
        assertEquals(BuilderRelayInstall.Resolution.AS_IS, original.resolution());
        assertEquals(original, roundTrip(original));
    }

    @Test
    @DisplayName("a resolved request carries the choice and the chosen name")
    void resolvedRequestRoundTrip() {
        for (BuilderRelayInstall.Resolution resolution : BuilderRelayInstall.Resolution.values()) {
            BuilderProfileDownloadPacket original =
                    new BuilderProfileDownloadPacket(4271, resolution, "brick_cabin_2", "");
            assertEquals(original, roundTrip(original),
                    "the second press must survive the wire for " + resolution);
        }
    }

    private static BuilderProfileDownloadPacket roundTrip(BuilderProfileDownloadPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        BuilderProfileDownloadPacket.STREAM_CODEC.encode(buf, packet);
        return BuilderProfileDownloadPacket.STREAM_CODEC.decode(buf);
    }

    @Test
    @DisplayName("a request for somebody else's build names whose it is")
    void foreignRequestRoundTrip() {
        BuilderProfileDownloadPacket original =
                new BuilderProfileDownloadPacket(4271, "2b1f9e00-0000-4000-8000-00000000abcd");
        assertEquals("2b1f9e00-0000-4000-8000-00000000abcd", original.ownerUuid());
        assertEquals(original, roundTrip(original));
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
