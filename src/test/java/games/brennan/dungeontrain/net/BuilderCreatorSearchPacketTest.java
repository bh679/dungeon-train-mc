package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The creator search's wire format, and the one rule that decides whose builds a profile request is
 * answered about.
 *
 * <p>That rule is the security boundary of the whole feature — a release build must answer a request
 * naming somebody else with the caller's own profile — so it is pinned here rather than left to a
 * branch nobody exercises: every release jar takes the {@code devBuild == false} path and nothing in
 * game would ever show that it had stopped working.</p>
 */
final class BuilderCreatorSearchPacketTest {

    private static final String MINE = "11111111-1111-4111-8111-111111111111";
    private static final String THEIRS = "22222222-2222-4222-8222-222222222222";

    @Test
    @DisplayName("a release build answers about the caller, whoever the packet named")
    void releaseBuildIgnoresARequestedOwner() {
        assertEquals(MINE, BuilderProfileRequestPacket.viewedOwner(MINE, THEIRS, false));
        assertEquals(MINE, BuilderProfileRequestPacket.viewedOwner(MINE, "", false));
    }

    @Test
    @DisplayName("a dev build honours the named owner, and an empty one still means me")
    void devBuildHonoursARequestedOwner() {
        assertEquals(THEIRS, BuilderProfileRequestPacket.viewedOwner(MINE, THEIRS, true));
        assertEquals(THEIRS, BuilderProfileRequestPacket.viewedOwner(MINE, "  " + THEIRS + " ", true));
        assertEquals(MINE, BuilderProfileRequestPacket.viewedOwner(MINE, "", true));
        assertEquals(MINE, BuilderProfileRequestPacket.viewedOwner(MINE, "   ", true));
        assertEquals(MINE, BuilderProfileRequestPacket.viewedOwner(MINE, null, true));
    }

    @Test
    @DisplayName("a release build never addresses the live relay, however the packet asked")
    void releaseBuildIgnoresTheLiveFlag() {
        // On a dev worktree isDevBuild() is true, so the honoured direction is what runs here; the
        // refusing direction is the branch a release jar takes and is asserted through the same helper.
        assertEquals(games.brennan.dungeontrain.DungeonTrain.isDevBuild(),
                BuilderProfileRequestPacket.liveRequested(true),
                "live is honoured on a dev build and refused on a release one");
        assertFalse(BuilderProfileRequestPacket.liveRequested(false), "not asked for is never live");
    }

    @Test
    @DisplayName("the profile request carries the owner it asks about")
    void requestRoundTrip() {
        assertEquals("", new BuilderProfileRequestPacket().ownerUuid(), "the ordinary ask is about me");
        assertFalse(new BuilderProfileRequestPacket().live(), "the ordinary ask is this build's own relay");
        BuilderProfileRequestPacket original = new BuilderProfileRequestPacket(THEIRS, true);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        BuilderProfileRequestPacket.STREAM_CODEC.encode(buf, original);
        assertEquals(original, BuilderProfileRequestPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("a profile reply says whose builds it is, so a late answer can be recognised")
    void profileReplyRoundTrip() {
        BuilderProfilePacket original = new BuilderProfilePacket(BuilderProfilePacket.Status.OK,
                List.of(new BuilderProfilePacket.Entry(7, "carriage", "", "brick_cabin", true, "approved",
                        BuilderReviewState.SUBMITTED, "stone", 3)),
                THEIRS, "", false);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        BuilderProfilePacket.STREAM_CODEC.encode(buf, original);
        BuilderProfilePacket back = BuilderProfilePacket.STREAM_CODEC.decode(buf);
        assertEquals(original, back);
        assertFalse(back.mine());

        BuilderProfilePacket own = BuilderProfilePacket.of(BuilderProfilePacket.Status.OK, MINE, "Brennan", true);
        FriendlyByteBuf ownBuf = new FriendlyByteBuf(Unpooled.buffer());
        BuilderProfilePacket.STREAM_CODEC.encode(ownBuf, own);
        BuilderProfilePacket ownBack = BuilderProfilePacket.STREAM_CODEC.decode(ownBuf);
        assertTrue(ownBack.mine());
        assertEquals("Brennan", ownBack.ownerName());
    }

    @Test
    @DisplayName("a search and its results survive the wire, query included")
    void searchRoundTrip() {
        BuilderCreatorSearchPacket query = new BuilderCreatorSearchPacket("bren", true);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        BuilderCreatorSearchPacket.STREAM_CODEC.encode(buf, query);
        assertEquals(query, BuilderCreatorSearchPacket.STREAM_CODEC.decode(buf));

        BuilderCreatorResultsPacket results = new BuilderCreatorResultsPacket("bren", true,
                List.of(new BuilderCreatorResultsPacket.Creator(THEIRS, "Brennan", 12)));
        FriendlyByteBuf resultBuf = new FriendlyByteBuf(Unpooled.buffer());
        BuilderCreatorResultsPacket.STREAM_CODEC.encode(resultBuf, results);
        assertEquals(results, BuilderCreatorResultsPacket.STREAM_CODEC.decode(resultBuf));
    }

    @Test
    @DisplayName("nobody matched and cannot search are different answers")
    void emptyIsNotTheSameAsUnavailable() {
        BuilderCreatorResultsPacket unavailable = BuilderCreatorResultsPacket.empty("bren");
        assertFalse(unavailable.found());
        assertTrue(unavailable.creators().isEmpty());

        BuilderCreatorResultsPacket none = BuilderCreatorResultsPacket.of("bren", List.of());
        assertTrue(none.found(), "the relay answered — there is simply nobody by that name");
        assertTrue(none.creators().isEmpty());

        assertFalse(BuilderCreatorResultsPacket.of("bren", null).found(), "an unreachable relay is not an empty pool");
    }
}
