package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The rule that merges a pair's two corridor scans into one list.
 *
 * <p>Both callers — {@link PortalEntityTransit} and {@link PortalPuppets} — read this one list, so
 * it has to hold each entity exactly once and in a stable order. It used to do that with a linear
 * {@code contains} over the list it was building, which is fine for the handful of entities a
 * corridor was assumed to hold and quadratic for what an authored room actually holds: 64 mobs by
 * {@link PortalRoomMobs#MAX_LIVE_PER_STRUCTURE}, plus everything a fight in it drops, every tick.
 * The rule is pinned here rather than only in game because the fix changed the data structure
 * underneath it and must not have changed the answer.</p>
 */
class PortalCorridorEntitiesTest {

    /** Identity, matching the reference comparison the old list scan did. */
    private static <T> Set<T> seen() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** Two of these can be equal without being the same occupant. */
    private record Occupant(int id) {}

    @Test
    @DisplayName("An entity in both scans is listed once, at its first sighting")
    void overlapIsMergedNotDuplicated() {
        Object shared = new Object();
        Object carriageOnly = new Object();
        Object twinOnly = new Object();

        Set<Object> seen = seen();
        List<Object> out = new ArrayList<>();
        PortalCorridorEntities.addNew(List.of(carriageOnly, shared), seen, out);
        PortalCorridorEntities.addNew(List.of(shared, twinOnly), seen, out);

        assertEquals(3, out.size(), "the entity in both corridors was listed twice");
        assertSame(carriageOnly, out.get(0));
        assertSame(shared, out.get(1));
        assertSame(twinOnly, out.get(2));
    }

    @Test
    @DisplayName("Order is the order the level reported, across both scans")
    void orderIsPreserved() {
        List<Object> carriage = new ArrayList<>();
        for (int i = 0; i < 5; i++) carriage.add(new Object());
        List<Object> twin = new ArrayList<>();
        for (int i = 0; i < 5; i++) twin.add(new Object());

        Set<Object> seen = seen();
        List<Object> out = new ArrayList<>();
        PortalCorridorEntities.addNew(carriage, seen, out);
        PortalCorridorEntities.addNew(twin, seen, out);

        List<Object> expected = new ArrayList<>(carriage);
        expected.addAll(twin);
        assertEquals(expected, out);
    }

    @Test
    @DisplayName("Equal-but-distinct entities are both kept")
    void identityNotEquality() {
        // Two entities are the same occupant only if they are the same object. A record that is
        // equal but not identical stands in for that here: switching to a plain HashSet would
        // silently drop the second, and a corridor would lose an occupant.
        Occupant first = new Occupant(7);
        Occupant second = new Occupant(7);

        Set<Occupant> seen = seen();
        List<Occupant> out = new ArrayList<>();
        PortalCorridorEntities.addNew(List.of(first, second), seen, out);

        assertEquals(2, out.size(), "two distinct occupants were merged by value equality");
    }

    @Test
    @DisplayName("An empty corridor contributes nothing")
    void emptyIsFine() {
        Set<Object> seen = seen();
        List<Object> out = new ArrayList<>();
        PortalCorridorEntities.addNew(List.of(), seen, out);

        assertEquals(0, out.size());
    }
}
