package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.nap.NapBinding.Decision;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A nap wakes in exactly one world: {@link NapBinding} and the bound field through {@link NapStore}. */
final class NapBindingTest {

    private static final String NAPPED = "World 1700000000000";
    private static final String HERE = "World 1700000000001";
    private static final String ELSEWHERE = "World 1700000000002";

    private static NapSnapshot nap() {
        CompoundTag player = new CompoundTag();
        player.putInt("XpLevel", 30);
        return new NapSnapshot(UUID.randomUUID(), 123L, "0.1840.0", NAPPED, player,
            -5120.5, -149, "Overworld", -1, 312, false, "normal");
    }

    @Test
    @DisplayName("binding survives the store and NBT round trip")
    void bindRoundTrip(@TempDir Path root) throws IOException {
        NapStore store = new NapStore(root);
        UUID player = UUID.randomUUID();
        NapSnapshot n = nap();
        store.save(player, "survival-normal", n);
        NapSnapshot bound = store.bind(player, "survival-normal", n, HERE);
        NapSnapshot back = store.load(player, "survival-normal").orElseThrow();
        assertEquals(bound, back);
        assertEquals(HERE, back.wakeLevelId());
        assertEquals(NAPPED, back.levelId(), "the source world is kept");
        assertTrue(back.bound());
    }

    @Test
    @DisplayName("a nap saved before binding existed reads as unbound and wakes")
    void legacyUnbound() {
        CompoundTag tag = nap().toTag();
        assertFalse(tag.contains("wakeLevelId"));
        NapSnapshot legacy = NapSnapshot.fromTag(tag);
        assertFalse(legacy.bound());
        assertEquals(Decision.WAKE, NapBinding.decide(legacy, HERE, false, false));
        assertTrue(NapBinding.waitsForNewWorld(legacy, false));
    }

    @Test
    @DisplayName("a nap bound to a world that still exists is ignored elsewhere")
    void boundElsewhereIgnored() {
        NapSnapshot n = nap().boundTo(ELSEWHERE);
        assertEquals(Decision.IGNORE, NapBinding.decide(n, HERE, true, false));
        assertFalse(NapBinding.waitsForNewWorld(n, true), "title screen must not read Reboard");
    }

    @Test
    @DisplayName("reopening the bound world with the player saved there uses the nap up")
    void boundHereWithSavedPlayerConsumes() {
        assertEquals(Decision.CONSUME, NapBinding.decide(nap().boundTo(HERE), HERE, true, true));
    }

    @Test
    @DisplayName("reopening the bound world with no saved player wakes it again")
    void boundHereWithoutSavedPlayerWakes() {
        assertEquals(Decision.WAKE, NapBinding.decide(nap().boundTo(HERE), HERE, true, false));
    }

    @Test
    @DisplayName("a nap bound to a world that is gone wakes in the next new world")
    void missingBoundWorldUnbinds() {
        NapSnapshot n = nap().boundTo(ELSEWHERE);
        assertEquals(Decision.WAKE, NapBinding.decide(n, HERE, false, false));
        assertTrue(NapBinding.waitsForNewWorld(n, false));
        assertEquals(HERE, n.boundTo(HERE).wakeLevelId(), "rebinding replaces the old world");
    }

    @Test
    @DisplayName("pending lists every slot's nap for the player, bound or not")
    void pendingAcrossSlots(@TempDir Path root) throws IOException {
        NapStore store = new NapStore(root);
        UUID player = UUID.randomUUID();
        NapSnapshot a = nap();
        NapSnapshot b = nap();
        store.save(player, "survival-normal", a);
        store.bind(player, "freeplay-hard", b, HERE);
        store.save(UUID.randomUUID(), "survival-normal", nap());
        List<NapStore.Pending> pending = store.pending(player);
        assertEquals(2, pending.size());
        assertTrue(pending.stream().anyMatch(p -> p.slot().equals("freeplay-hard") && HERE.equals(p.nap().wakeLevelId())));
        store.consume(player, "freeplay-hard", b.boundTo(HERE));
        assertEquals(1, store.pending(player).size());
    }
}
