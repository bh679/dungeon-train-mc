package games.brennan.dungeontrain.client.snapshot;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link DeathBackgroundAssigner}: preferred-first, fallbacks
 * prefer photos not used on another page, graceful reuse when images run short, and
 * an empty gallery yields all-null. {@code ResourceLocation} is constructed directly
 * (no NeoForge bootstrap); the assigner only reads each shot's tag + identity.
 */
final class DeathBackgroundAssignerTest {

    private static int seq = 0;

    private static RideSnapshot shot(SnapshotTag tag) {
        int s = seq++;
        return new RideSnapshot(ResourceLocation.fromNamespaceAndPath("dungeontrain", "ride_" + s), tag, 64, 36, s);
    }

    /** The death screen's page order: FALL, DEEDS, GEAR, LIVES, SURVEY, PLATFORM. */
    private static List<List<SnapshotTag>> standardChains() {
        return List.of(
                List.of(SnapshotTag.SCENIC),                         // FALL
                List.of(SnapshotTag.COMBAT, SnapshotTag.SCENIC),     // DEEDS
                List.of(SnapshotTag.GEAR, SnapshotTag.SCENIC),       // GEAR
                List.of(SnapshotTag.SOCIAL, SnapshotTag.SCENIC),     // LIVES
                List.of(),                                           // SURVEY (wildcard)
                List.of());                                          // PLATFORM (wildcard)
    }

    private static void assertNoDuplicates(RideSnapshot[] out) {
        Set<RideSnapshot> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RideSnapshot s : out) {
            if (s != null) assertTrue(seen.add(s), "duplicate background assigned: " + s);
        }
    }

    @Test
    @DisplayName("each data page gets its preferred tag; wildcards take remaining unused")
    void preferredFirst_distinct() {
        List<RideSnapshot> g = new ArrayList<>();
        RideSnapshot scenic1 = shot(SnapshotTag.SCENIC); g.add(scenic1);
        RideSnapshot combat1 = shot(SnapshotTag.COMBAT); g.add(combat1);
        RideSnapshot gear1 = shot(SnapshotTag.GEAR);     g.add(gear1);
        RideSnapshot social1 = shot(SnapshotTag.SOCIAL); g.add(social1);
        RideSnapshot scenic2 = shot(SnapshotTag.SCENIC); g.add(scenic2);
        RideSnapshot combat2 = shot(SnapshotTag.COMBAT); g.add(combat2);

        RideSnapshot[] out = DeathBackgroundAssigner.assign(standardChains(), g);

        assertSame(scenic2, out[0], "FALL -> newest SCENIC");
        assertSame(combat2, out[1], "DEEDS -> newest COMBAT");
        assertSame(gear1, out[2], "GEAR");
        assertSame(social1, out[3], "LIVES -> SOCIAL");
        assertSame(combat1, out[4], "SURVEY -> newest unused (combat1)");
        assertSame(scenic1, out[5], "PLATFORM -> next unused (scenic1)");
        assertNoDuplicates(out);
    }

    @Test
    @DisplayName("fallback prefers UNUSED along the chain (no repeats while shots remain)")
    void fallback_prefersUnused() {
        List<RideSnapshot> g = new ArrayList<>();
        for (int i = 0; i < 6; i++) g.add(shot(SnapshotTag.SCENIC)); // only scenics
        RideSnapshot[] out = DeathBackgroundAssigner.assign(standardChains(), g);
        // 6 scenics, 6 pages → every page a distinct scenic.
        assertNoDuplicates(out);
        for (RideSnapshot s : out) assertSame(s.getClass(), RideSnapshot.class); // all non-null
        assertSame(g.get(5), out[0], "FALL -> newest scenic");
    }

    @Test
    @DisplayName("reuses the best-fit shot when there are fewer images than pages")
    void degradesToReuse() {
        List<RideSnapshot> g = List.of(shot(SnapshotTag.SCENIC));
        RideSnapshot only = g.get(0);
        RideSnapshot[] out = DeathBackgroundAssigner.assign(standardChains(), g);
        for (int i = 0; i < out.length; i++) assertSame(only, out[i], "page " + i + " reuses the lone shot");
    }

    @Test
    @DisplayName("empty gallery -> all null (caller paints the solid overlay)")
    void emptyGallery_allNull() {
        RideSnapshot[] out = DeathBackgroundAssigner.assign(standardChains(), List.of());
        assertEquals(6, out.length);
        for (RideSnapshot s : out) assertNull(s);
    }

    @Test
    @DisplayName("two pages sharing a preferred tag get different shots")
    void sharedPreferredTag_noCollision() {
        List<List<SnapshotTag>> chains = List.of(List.of(SnapshotTag.SCENIC), List.of(SnapshotTag.SCENIC));
        List<RideSnapshot> g = new ArrayList<>();
        RideSnapshot a = shot(SnapshotTag.SCENIC); g.add(a);
        RideSnapshot b = shot(SnapshotTag.SCENIC); g.add(b);
        RideSnapshot[] out = DeathBackgroundAssigner.assign(chains, g);
        assertSame(b, out[0], "page 0 -> newest scenic");
        assertSame(a, out[1], "page 1 -> next unused scenic");
        assertNotSame(out[0], out[1]);
    }

    @Test
    @DisplayName("deterministic: same input yields the same assignment")
    void deterministic() {
        List<RideSnapshot> g = new ArrayList<>();
        g.add(shot(SnapshotTag.SCENIC));
        g.add(shot(SnapshotTag.COMBAT));
        g.add(shot(SnapshotTag.GEAR));
        RideSnapshot[] a = DeathBackgroundAssigner.assign(standardChains(), g);
        RideSnapshot[] b = DeathBackgroundAssigner.assign(standardChains(), g);
        assertArrayEquals(a, b);
    }
}
