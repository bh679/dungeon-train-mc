package games.brennan.dungeontrain.world;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NBT round-trip and take semantics for pets waiting on an offline owner. */
class PendingPetsTest {

    private static CompoundTag wolf(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:wolf");
        tag.putString("CustomName", name);
        return tag;
    }

    @Test
    void petsSurviveASaveAndLoad() {
        UUID owner = UUID.randomUUID();
        PendingPets pending = PendingPets.load(new CompoundTag());
        pending.add(owner, wolf("Rex"));
        pending.add(owner, wolf("Bea"));

        PendingPets reloaded = PendingPets.load(pending.save(new CompoundTag(), null));
        List<CompoundTag> pets = reloaded.take(owner);
        assertEquals(2, pets.size());
        assertEquals("Rex", pets.get(0).getString("CustomName"));
        assertEquals("Bea", pets.get(1).getString("CustomName"));
    }

    @Test
    void takeEmptiesTheOwnersList() {
        UUID owner = UUID.randomUUID();
        PendingPets pending = PendingPets.load(new CompoundTag());
        pending.add(owner, wolf("Rex"));
        assertTrue(pending.has(owner));
        assertEquals(1, pending.take(owner).size());
        assertFalse(pending.has(owner));
        assertTrue(pending.take(owner).isEmpty());
    }

    @Test
    void oldestPetGoesPastTheCap() {
        UUID owner = UUID.randomUUID();
        PendingPets pending = PendingPets.load(new CompoundTag());
        for (int i = 0; i <= PendingPets.MAX_PER_OWNER; i++) pending.add(owner, wolf("p" + i));
        List<CompoundTag> pets = pending.take(owner);
        assertEquals(PendingPets.MAX_PER_OWNER, pets.size());
        assertEquals("p1", pets.get(0).getString("CustomName"));
    }
}
