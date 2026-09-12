package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import games.brennan.dungeontrain.builder.relay.BuilderRelayInstall;
import games.brennan.dungeontrain.editor.TemplateLootPrefabs;
import io.netty.buffer.Unpooled;

import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                    new BuilderProfileDownloadPacket(4271, resolution, "brick_cabin_2", "", "", false, false);
            assertEquals(original, roundTrip(original),
                    "the second press must survive the wire for " + resolution);
        }
    }

    @Test
    @DisplayName("a first press never confirms overwriting unsaved edits")
    void firstPressDoesNotConfirmOverwrite() {
        assertFalse(new BuilderProfileDownloadPacket(4271).overwriteUnsaved(),
                "the unsaved-edits question has to be asked before anything is written");
        assertFalse(new BuilderProfileDownloadPacket(4271, "", "", false).overwriteUnsaved());
    }

    @Test
    @DisplayName("the answer to the unsaved-edits question survives the wire with its resolution")
    void overwriteUnsavedRoundTrip() {
        BuilderProfileDownloadPacket original = new BuilderProfileDownloadPacket(
                4271, BuilderRelayInstall.Resolution.LOAD_AS_NEW, "brick_cabin_2", "", "", false, true);
        BuilderProfileDownloadPacket back = roundTrip(original);
        assertTrue(back.overwriteUnsaved(), "a confirmed overwrite that arrived as false would re-ask forever");
        assertEquals(BuilderRelayInstall.Resolution.LOAD_AS_NEW, back.resolution(),
                "the collision answer given on the earlier press must ride along");
        assertEquals(original, back);
    }

    @Test
    @DisplayName("the variant parent a build is filed under rides along, and is blank for a plain load")
    void parentIdRoundTrip() {
        BuilderProfileDownloadPacket original = new BuilderProfileDownloadPacket(
                4271, BuilderRelayInstall.Resolution.AS_IS, "", "", "", false, false, "user_builds");
        BuilderProfileDownloadPacket back = roundTrip(original);
        assertEquals("user_builds", back.parentId(), "a lost parent would land the build at top level unasked");
        assertEquals(original, back);
        assertEquals("", new BuilderProfileDownloadPacket(4271).parentId(), "a plain load names no parent");
        assertEquals("", new BuilderProfileDownloadPacket(4271, "", "", false).parentId());
        assertEquals("", new BuilderProfileDownloadPacket(
                4271, BuilderRelayInstall.Resolution.AS_IS, "", "", "", false, false, null).parentId(),
                "null normalises to blank so the codec never sees it");
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
                new BuilderProfileDownloadPacket(4271, "2b1f9e00-0000-4000-8000-00000000abcd", "Edda", true);
        assertEquals("2b1f9e00-0000-4000-8000-00000000abcd", original.ownerUuid());
        assertEquals("Edda", original.ownerName(), "the byline the screen showed has to reach the install");
        assertTrue(original.live(), "a build is fetched from the pool it was listed in");
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
    @DisplayName("a naming question carries the names already in use, and others carry none")
    void takenNamesRoundTrip() {
        BuilderProfileDownloadResultPacket asking = new BuilderProfileDownloadResultPacket(
                BuilderRelayDownload.Outcome.ALREADY_HERE, "portal_room", "testtt", "",
                List.of("testtt", "testtt_2"));
        BuilderProfileDownloadResultPacket back = roundTrip(asking);
        assertEquals(List.of("testtt", "testtt_2"), back.takenNames(),
                "the name box opens on this list — a truncated one offers a name that is gone");
        assertEquals(asking, back);

        assertEquals(List.of(), new BuilderProfileDownloadResultPacket(
                        BuilderRelayDownload.Outcome.INSTALLED, "portal_room", "testtt", "").takenNames(),
                "an outcome that asks for no name carries no list");
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

    @Test
    @DisplayName("the loot-prefab answer survives the wire, and a first press has none")
    void prefabAnswerRoundTrip() {
        BuilderProfileDownloadPacket first = new BuilderProfileDownloadPacket(
                4271, BuilderRelayInstall.Resolution.LOAD_AS_NEW, "brick_cabin_2", "", "", false, true, "user_builds");
        assertFalse(first.prefabsResolved(), "the prefab question has to be asked before anything is written");
        assertEquals(List.of(), first.prefabOverwrite());

        BuilderProfileDownloadPacket answered = first.answeringPrefabs(List.of("gold", "silver"));
        BuilderProfileDownloadPacket back = roundTrip(answered);
        assertTrue(back.prefabsResolved(), "an answer that arrived unresolved would re-ask forever");
        assertEquals(List.of("gold", "silver"), back.prefabOverwrite());
        assertEquals(answered, back);
        // Everything the earlier presses settled rides along unchanged.
        assertEquals(BuilderRelayInstall.Resolution.LOAD_AS_NEW, back.resolution());
        assertEquals("brick_cabin_2", back.name());
        assertTrue(back.overwriteUnsaved());
        assertEquals("user_builds", back.parentId());

        BuilderProfileDownloadPacket keepAll = first.answeringPrefabs(List.of());
        assertTrue(roundTrip(keepAll).prefabsResolved(), "keeping every local prefab is still an answer");
    }

    @Test
    @DisplayName("a prefab question carries both versions of every conflicting prefab")
    void conflictsRoundTrip() {
        List<TemplateLootPrefabs.Conflict> conflicts = List.of(
                new TemplateLootPrefabs.Conflict("gold", "{\"entries\":[1]}", "{\"entries\":[2]}"),
                new TemplateLootPrefabs.Conflict("silver", "{}", "{\"block\":\"minecraft:barrel\"}"));
        BuilderProfileDownloadResultPacket asking = new BuilderProfileDownloadResultPacket(
                BuilderRelayDownload.Outcome.PREFAB_CONFLICT, "contents", "vault", "", List.of(), conflicts);
        BuilderProfileDownloadResultPacket back = roundTrip(asking);
        assertEquals(conflicts, back.conflicts(), "the screen compares exactly these two texts");
        assertEquals(asking, back);
        assertEquals(List.of(), new BuilderProfileDownloadResultPacket(
                        BuilderRelayDownload.Outcome.INSTALLED, "contents", "vault", "").conflicts(),
                "an outcome that asks about no prefab carries none");
    }

    @Test
    @DisplayName("a conflict text too long for the wire is cut, not dropped")
    void oversizeConflictTextIsClipped() {
        String huge = "x".repeat(BuilderProfileDownloadResultPacket.MAX_CONFLICT_TEXT + 500);
        BuilderProfileDownloadResultPacket asking = new BuilderProfileDownloadResultPacket(
                BuilderRelayDownload.Outcome.PREFAB_CONFLICT, "contents", "vault", "", List.of(),
                List.of(new TemplateLootPrefabs.Conflict("gold", huge, "{}")));
        BuilderProfileDownloadResultPacket back = roundTrip(asking);
        assertEquals(1, back.conflicts().size(), "the prefab is still listed, and the choice still offered");
        assertEquals(BuilderProfileDownloadResultPacket.MAX_CONFLICT_TEXT, back.conflicts().get(0).localText().length());
        assertEquals("{}", back.conflicts().get(0).incomingText());
    }

    private static BuilderProfileDownloadResultPacket roundTrip(BuilderProfileDownloadResultPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        BuilderProfileDownloadResultPacket.STREAM_CODEC.encode(buf, packet);
        return BuilderProfileDownloadResultPacket.STREAM_CODEC.decode(buf);
    }
}
