package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shulker box loot entry naming a loot prefab ({@code contents}) rolls packed with a roll of that prefab:
 * fixed for one outer roll, different per slot, one level deep, and saved with the prefab.
 */
final class ContainerContentsShulkerTest {

    private static final ResourceLocation SHULKER = ResourceLocation.withDefaultNamespace("shulker_box");
    private static final ResourceLocation RED_SHULKER = ResourceLocation.withDefaultNamespace("red_shulker_box");
    private static final ResourceLocation DIAMOND = ResourceLocation.withDefaultNamespace("diamond");
    private static final ResourceLocation BREAD = ResourceLocation.withDefaultNamespace("bread");
    private static final BlockPos CELL = new BlockPos(3, 1, 2);
    private static final long SEED = 987654321L;

    private static HolderLookup.Provider registries;
    private Function<String, Optional<ContainerContentsPool>> previousLookup;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
    }

    @AfterEach
    void restoreLookup() {
        if (previousLookup != null) ContainerContentsRoller.setContentsLookup(previousLookup);
    }

    private void usePrefabs(Map<String, ContainerContentsPool> prefabs) {
        previousLookup = ContainerContentsRoller.setContentsLookup(id -> Optional.ofNullable(prefabs.get(id)));
    }

    /** Diamonds and bread, 5-10 of the 27 slots filled. */
    private static ContainerContentsPool supplies() {
        return new ContainerContentsPool(List.of(
            new ContainerContentsEntry(DIAMOND, 4, 1),
            new ContainerContentsEntry(BREAD, 16, 3)), 5, 10);
    }

    private static ContainerContentsEntry packedShulker(String prefab) {
        return new ContainerContentsEntry(SHULKER, 1, 1).withContents(prefab);
    }

    private static List<ItemStack> contentsOf(ItemStack box) {
        ItemContainerContents c = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        return c.nonEmptyStream().toList();
    }

    private static ItemStack roll(ContainerContentsEntry e, int slot) {
        return ContainerContentsRoller.rollItemStack(e, 1, CELL, SEED, 40, 40, slot, registries);
    }

    @Test
    @DisplayName("a shulker naming a prefab comes out packed with that prefab's items, within its fill range")
    void packsFromPrefab() {
        usePrefabs(Map.of("supplies", supplies()));
        ItemStack box = roll(packedShulker("supplies"), 0);
        assertTrue(box.is(Items.SHULKER_BOX));
        List<ItemStack> inside = contentsOf(box);
        assertTrue(inside.size() >= 5 && inside.size() <= 10, "fill range 5-10, got " + inside.size());
        for (ItemStack s : inside) assertTrue(s.is(Items.DIAMOND) || s.is(Items.BREAD), s.toString());
    }

    @Test
    @DisplayName("the same outer roll packs the same box; another slot packs a different one")
    void deterministicPerSlot() {
        usePrefabs(Map.of("supplies", supplies()));
        ContainerContentsEntry e = packedShulker("supplies");
        List<ItemStack> a = contentsOf(roll(e, 3));
        List<ItemStack> again = contentsOf(roll(e, 3));
        assertEquals(describe(a), describe(again));
        boolean anyDiffers = false;
        for (int slot = 4; slot < 12 && !anyDiffers; slot++) {
            anyDiffers = !describe(contentsOf(roll(e, slot))).equals(describe(a));
        }
        assertTrue(anyDiffers, "boxes rolled in other slots should not all match slot 3's");
    }

    @Test
    @DisplayName("one level deep: a prefab holding packed shulkers, itself included, rolls them empty and ends")
    void noRecursion() {
        ContainerContentsPool selfish = new ContainerContentsPool(List.of(packedShulker("selfish")), 3, 3);
        usePrefabs(Map.of("selfish", selfish));
        List<ItemStack> inside = contentsOf(roll(packedShulker("selfish"), 0));
        assertEquals(3, inside.size());
        for (ItemStack s : inside) {
            assertTrue(s.is(Items.SHULKER_BOX));
            assertTrue(contentsOf(s).isEmpty(), "an inner shulker is never packed");
        }
    }

    @Test
    @DisplayName("a prefab that does not exist leaves the box empty")
    void missingPrefabIsEmpty() {
        usePrefabs(Map.of());
        ItemStack box = roll(packedShulker("nowhere"), 0);
        assertTrue(box.is(Items.SHULKER_BOX));
        assertTrue(contentsOf(box).isEmpty());
    }

    @Test
    @DisplayName("contents only mean something on a shulker box, and only a valid prefab name is kept")
    void onlyShulkersCarryContents() {
        assertTrue(new ContainerContentsEntry(RED_SHULKER, 1, 1).withContents("supplies").hasContents());
        assertFalse(new ContainerContentsEntry(DIAMOND, 1, 1).withContents("supplies").hasContents());
        assertNull(packedShulker("Not A Name!").contents());
        usePrefabs(Map.of("supplies", supplies()));
        ItemStack diamond = roll(new ContainerContentsEntry(DIAMOND, 1, 1).withContents("supplies"), 0);
        assertFalse(diamond.has(DataComponents.CONTAINER));
    }

    @Test
    @DisplayName("a vault paying out two packed shulkers rolls each one on its own")
    void vaultExtrasRollSeparately() {
        usePrefabs(Map.of("supplies", supplies()));
        ContainerContentsPool vault = new ContainerContentsPool(
            List.of(new ContainerContentsEntry(SHULKER, 2, 1).withMinCount(2).withContents("supplies")), 1, 1);
        List<ItemStack> out = ContainerContentsRoller.rollStacks(vault, 9, CELL, SEED, 40, 40, true, registries);
        assertEquals(2, out.size());
        assertNotEquals(describe(contentsOf(out.get(0))), describe(contentsOf(out.get(1))));
    }

    @Test
    @DisplayName("contents survive the prefab file round trip, and are not written for other items")
    void prefabRoundTrip() {
        ContainerContentsPool pool = new ContainerContentsPool(List.of(
            packedShulker("supplies"), new ContainerContentsEntry(DIAMOND, 1, 1)));
        String text = LootPrefabStore.poolText(pool, ResourceLocation.withDefaultNamespace("vault"));
        assertTrue(text.contains("\"contents\": \"supplies\""));
        ContainerContentsPool back = LootPrefabStore.poolFromText(text).orElseThrow();
        assertEquals("supplies", back.entries().get(0).contents());
        assertNull(back.entries().get(1).contents());
        assertEquals(pool.entries(), back.entries());
    }

    private static String describe(List<ItemStack> stacks) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack s : stacks) sb.append(s.getItem()).append('x').append(s.getCount()).append(';');
        return sb.toString();
    }
}
