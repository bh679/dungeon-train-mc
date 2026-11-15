package games.brennan.dungeontrain.nap;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NapSnapshot} round trip and {@link NapStore}'s single-use rule. */
final class NapStoreTest {

    private static NapSnapshot nap(String levelId) {
        CompoundTag player = new CompoundTag();
        player.putInt("XpLevel", 30);
        return new NapSnapshot(UUID.randomUUID(), 123L, "0.1840.0", levelId, player,
            -5120.5, -149, "Overworld (BoP)", -1, 312, true, "hard");
    }

    @Test
    @DisplayName("a nap survives the trip through NBT unchanged")
    void roundTrip() {
        NapSnapshot n = nap("World 1700000000000");
        assertEquals(n, NapSnapshot.fromTag(n.toTag()));
        assertEquals(null, NapSnapshot.fromTag(new CompoundTag()));
    }

    @Test
    @DisplayName("a nap wakes once: a copy put back after waking is refused")
    void singleUse(@TempDir Path root) throws Exception {
        NapStore store = new NapStore(root);
        UUID player = UUID.randomUUID();
        NapSnapshot n = nap("World 1700000000001");
        store.save(player, n);
        assertEquals(Set.of("World 1700000000001"), store.pendingLevelIds());
        Path copy = root.resolve("copy.bin");
        Files.copy(store.fileFor(player), copy);

        assertEquals(n, store.load(player).orElseThrow());
        store.consume(player, n);
        assertFalse(store.load(player).isPresent());

        Files.copy(copy, store.fileFor(player));
        assertFalse(store.load(player).isPresent());
        assertFalse(Files.exists(store.fileFor(player)), "the refused copy is deleted");
        assertTrue(store.pendingLevelIds().isEmpty());
    }
}
