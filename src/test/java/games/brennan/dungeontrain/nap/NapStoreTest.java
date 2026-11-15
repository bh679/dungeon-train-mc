package games.brennan.dungeontrain.nap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Difficulty;
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
        String slot = NapSlot.key(false, Difficulty.HARD);
        NapSnapshot n = nap("World 1700000000001");
        store.save(player, slot, n);
        assertEquals(Set.of("World 1700000000001"), store.pendingLevelIds());
        Path copy = root.resolve("copy.bin");
        Files.copy(store.fileFor(player, slot), copy);

        assertEquals(n, store.load(player, slot).orElseThrow());
        store.consume(player, slot, n);
        assertFalse(store.load(player, slot).isPresent());

        Files.copy(copy, store.fileFor(player, slot));
        assertFalse(store.load(player, slot).isPresent());
        assertFalse(Files.exists(store.fileFor(player, slot)), "the refused copy is deleted");
        assertTrue(store.pendingLevelIds().isEmpty());
    }

    @Test
    @DisplayName("Free Play, Survival and each difficulty keep their own nap")
    void slotsAreSeparate(@TempDir Path root) throws Exception {
        assertEquals("survival-normal", NapSlot.key(false, Difficulty.NORMAL));
        assertEquals("freeplay-hard", NapSlot.key(true, Difficulty.HARD));

        NapStore store = new NapStore(root);
        UUID player = UUID.randomUUID();
        NapSnapshot n = nap("World 1700000000002");
        store.save(player, NapSlot.key(false, Difficulty.NORMAL), n);
        assertTrue(store.load(player, NapSlot.key(false, Difficulty.NORMAL)).isPresent());
        assertFalse(store.load(player, NapSlot.key(true, Difficulty.NORMAL)).isPresent());
        assertFalse(store.load(player, NapSlot.key(false, Difficulty.HARD)).isPresent());
    }
}
