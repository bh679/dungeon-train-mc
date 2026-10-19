package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.net.relay.AdvancementRevokeClient.Revoke;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game half of a relay revocation, without the game: which earns a revocation covers, the
 * per-player cache the login reads before the relay answers, and the once-per-install profile marker.
 */
final class AdvancementRevocationsTest {

    private static final UUID A = UUID.fromString("dd529609-c01f-47a0-84cb-653d35980c51");
    private static final UUID B = UUID.fromString("587433bc-2e7a-452d-a242-fd2fe5da6e48");
    private static final ResourceLocation ALPHA = ResourceLocation.parse("dungeontrain:secrete_menu/reversed_alpha");
    private static final ResourceLocation REASSEMBLY = ResourceLocation.parse("dungeontrain:secrete_menu/reversed_reassembly");

    @Test
    @DisplayName("an earn at or before the revocation is covered; a later one is genuine")
    void coveredBy() {
        long ts = 1_790_939_523_517L;
        assertTrue(AdvancementRevocations.coveredBy(Instant.ofEpochMilli(ts - 86_400_000L), ts));
        assertTrue(AdvancementRevocations.coveredBy(Instant.ofEpochMilli(ts), ts));
        assertFalse(AdvancementRevocations.coveredBy(Instant.ofEpochMilli(ts + 1), ts));
        assertFalse(AdvancementRevocations.coveredBy(null, ts), "an unobtained criterion is nothing to revoke");
    }

    @Test
    @DisplayName("the cache keeps each player's list, and the relay's answer replaces it whole")
    void cacheReplace(@TempDir Path dir) {
        assertEquals(List.of(), AdvancementRevocations.cached(dir, A));
        List<Revoke> two = List.of(new Revoke("r_1", ALPHA, 10), new Revoke("r_2", REASSEMBLY, 10));
        AdvancementRevocations.replaceFor(dir, A, two);
        AdvancementRevocations.replaceFor(dir, B, List.of(new Revoke("r_3", ALPHA, 20)));
        assertEquals(two, AdvancementRevocations.cached(dir, A));

        // One cancelled on the relay: it drops out. All cancelled: the player's entry goes.
        AdvancementRevocations.replaceFor(dir, A, List.of(new Revoke("r_2", REASSEMBLY, 10)));
        assertEquals(List.of(new Revoke("r_2", REASSEMBLY, 10)), AdvancementRevocations.cached(dir, A));
        AdvancementRevocations.replaceFor(dir, A, List.of());
        assertEquals(List.of(), AdvancementRevocations.cached(dir, A));
        assertEquals(1, AdvancementRevocations.cached(dir, B).size(), "other players untouched");
    }

    @Test
    @DisplayName("a corrupt cache reads as empty instead of failing the login")
    void corruptCache(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(AdvancementRevocations.CACHE_FILE), "{not json");
        assertEquals(List.of(), AdvancementRevocations.cached(dir, A));
    }

    @Test
    @DisplayName("the applied marker records each revocation once")
    void appliedMarker(@TempDir Path dir) {
        assertFalse(AdvancementRevocations.isApplied(dir, "r_1"));
        AdvancementRevocations.markApplied(dir, "r_1");
        AdvancementRevocations.markApplied(dir, "r_1");
        AdvancementRevocations.markApplied(dir, "r_2");
        assertTrue(AdvancementRevocations.isApplied(dir, "r_1"));
        assertEquals(List.of("r_1", "r_2"), List.copyOf(AdvancementRevocations.appliedIds(dir)));
    }
}
