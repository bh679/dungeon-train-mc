package games.brennan.dungeontrain.loot;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A find gets its number once, in its data, and the waiting number is then spent. */
final class VaultFindStamperTest {

    private static final UUID CLAIM = UUID.fromString("00000000-0000-0000-0000-0000000000cd");

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A mace as {@code VaultFinds} leaves it before the answer: claim tag and an AIN line. */
    private static ItemStack pulledMace() {
        ItemStack stack = new ItemStack(Items.MACE);
        CompoundTag find = new CompoundTag();
        find.putUUID(VaultFinds.TAG_CLAIM, CLAIM);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(VaultFinds.TAG, find));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Heavy with old storms"))));
        return stack;
    }

    @Test
    @DisplayName("the number goes into the data, the description is untouched, and the waiting number is spent")
    void stampsOnce() {
        VaultFindSerials serials = VaultFindSerials.load(new CompoundTag());
        serials.put(CLAIM, 3);
        ItemStack mace = pulledMace();

        assertTrue(VaultFindStamper.tryStamp(mace, serials));
        assertEquals(3, VaultFinds.serialOf(mace).orElseThrow());
        assertEquals(1, mace.get(DataComponents.LORE).lines().size());
        assertTrue(serials.isEmpty());
        assertTrue(VaultFinds.pendingClaim(mace).isEmpty());

        serials.put(CLAIM, 9);                                       // a stray repeat never re-numbers
        assertFalse(VaultFindStamper.tryStamp(mace, serials));
        assertEquals(3, VaultFinds.serialOf(mace).orElseThrow());
    }

    @Test
    @DisplayName("a find whose number is not in yet is left alone")
    void waitsForItsNumber() {
        VaultFindSerials serials = VaultFindSerials.load(new CompoundTag());
        serials.put(UUID.randomUUID(), 1);
        ItemStack mace = pulledMace();
        assertFalse(VaultFindStamper.tryStamp(mace, serials));
        assertEquals(CLAIM, VaultFinds.pendingClaim(mace).orElseThrow());
        assertFalse(VaultFindStamper.tryStamp(new ItemStack(Items.MACE), serials));
    }

    @Test
    @DisplayName("waiting numbers survive a save, and the oldest go past the cap")
    void serialsRoundTrip() {
        VaultFindSerials serials = VaultFindSerials.load(new CompoundTag());
        UUID first = UUID.randomUUID();
        serials.put(first, 1);
        for (int i = 0; i < VaultFindSerials.MAX_PENDING; i++) serials.put(UUID.randomUUID(), 2);
        assertNull(serials.peek(first));
        serials.put(CLAIM, 7);
        VaultFindSerials loaded = VaultFindSerials.load(serials.save(new CompoundTag(), null));
        assertEquals(7, loaded.peek(CLAIM));
    }
}
