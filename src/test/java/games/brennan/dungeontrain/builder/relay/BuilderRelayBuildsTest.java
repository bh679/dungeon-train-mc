package games.brennan.dungeontrain.builder.relay;

import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record of what this world has uploaded — and, more to the point, of the credentials that go with
 * it. Losing an owner secret across a reload means a build that can never be published or edited
 * again, so the round trip is worth pinning.
 */
final class BuilderRelayBuildsTest {

    @Test
    @DisplayName("a key names the store, the sub kind and the id, so two stores can share a name")
    void keysSeparateStores() {
        assertNotEquals(BuilderRelayBuilds.keyOf("carriage", "", "quartz"),
                BuilderRelayBuilds.keyOf("portal_room", "", "quartz"));
        // 'standard' is both a floor part and a door part — the sub kind is what tells them apart.
        assertNotEquals(BuilderRelayBuilds.keyOf("part", "floor", "standard"),
                BuilderRelayBuilds.keyOf("part", "door", "standard"));
        assertEquals(BuilderRelayBuilds.keyOf("carriage", "", "brick_cabin"),
                BuilderRelayBuilds.keyOf("carriage", null, "brick_cabin"));
    }

    @Test
    @DisplayName("a key reads back the kind it was filed under — what decides if the pool has a say")
    void kindReadsBackOutOfTheKey() {
        assertEquals("carriage", BuilderRelayBuilds.kindOfKey(
                BuilderRelayBuilds.keyOf("carriage", "", "brick_cabin")));
        assertEquals("portal_room", BuilderRelayBuilds.kindOfKey(
                BuilderRelayBuilds.keyOf("portal_room", null, "library")));
        assertEquals("part", BuilderRelayBuilds.kindOfKey(
                BuilderRelayBuilds.keyOf("part", "floor", "standard")));
        // Nothing filed under a null or malformed key is a carriage, so no kind test can match it.
        assertEquals("", BuilderRelayBuilds.kindOfKey(null));
        assertEquals("", BuilderRelayBuilds.kindOfKey("carriage"));
    }

    @Test
    @DisplayName("entries survive a save/load round trip, credentials and all")
    void roundTrips() {
        BuilderRelayBuilds builds = new BuilderRelayBuilds();
        String cart = BuilderRelayBuilds.keyOf("carriage", "", "brick_cabin");
        String room = BuilderRelayBuilds.keyOf("portal_room", "", "library");
        builds.put(cart, new BuilderRelayBuilds.Entry(7, "sec-7", "tok-7", true));
        builds.put(room, new BuilderRelayBuilds.Entry(9, "sec-9", "", false));

        ListTag tag = builds.toTag();
        BuilderRelayBuilds reloaded = new BuilderRelayBuilds();
        reloaded.loadFrom(tag);

        assertEquals(new BuilderRelayBuilds.Entry(7, "sec-7", "tok-7", true), reloaded.get(cart));
        assertEquals(new BuilderRelayBuilds.Entry(9, "sec-9", "", false), reloaded.get(room));
        assertEquals(builds.keys(), reloaded.keys(), "order is the order things were built");
    }

    @Test
    @DisplayName("an empty world loads an absent tag as an empty profile rather than failing")
    void toleratesAbsentTag() {
        BuilderRelayBuilds builds = new BuilderRelayBuilds();
        builds.loadFrom(null);
        assertTrue(builds.isEmpty());
        builds.loadFrom(new ListTag());
        assertTrue(builds.isEmpty());
        assertNull(builds.get("anything"));
    }

    @Test
    @DisplayName("a relay id resolves back to its key — how a publish result finds what to update")
    void findsKeyByRelayId() {
        BuilderRelayBuilds builds = new BuilderRelayBuilds();
        String cart = BuilderRelayBuilds.keyOf("carriage", "", "brick_cabin");
        builds.put(cart, new BuilderRelayBuilds.Entry(7, "sec", "tok", false));
        assertEquals(cart, builds.keyForRelayId(7));
        assertNull(builds.keyForRelayId(8), "an id this world never uploaded is not ours to act on");
    }

    @Test
    @DisplayName("forgetting a build leaves the rest alone")
    void removesOne() {
        BuilderRelayBuilds builds = new BuilderRelayBuilds();
        String cart = BuilderRelayBuilds.keyOf("carriage", "", "a");
        String other = BuilderRelayBuilds.keyOf("carriage", "", "b");
        builds.put(cart, new BuilderRelayBuilds.Entry(1, "s", "t", false));
        builds.put(other, new BuilderRelayBuilds.Entry(2, "s", "t", false));
        builds.remove(cart);
        assertNull(builds.get(cart));
        assertEquals(2, builds.get(other).relayId());
        assertFalse(builds.isEmpty());
    }

    @Test
    @DisplayName("publishing clears the lease token it frees; withdrawing takes a new one")
    void tokenFollowsVisibility() {
        BuilderRelayBuilds.Entry held = new BuilderRelayBuilds.Entry(3, "sec", "tok", false);
        BuilderRelayBuilds.Entry onTrain = held.withPublished(true).withToken("");
        assertTrue(onTrain.published());
        assertEquals("", onTrain.token());
        BuilderRelayBuilds.Entry back = onTrain.withPublished(false).withToken("tok2");
        assertFalse(back.published());
        assertEquals("tok2", back.token());
        assertEquals("sec", back.secret(), "the secret outlives every lease");
    }
}
