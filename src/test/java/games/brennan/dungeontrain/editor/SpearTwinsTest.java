package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SpearTwinsTest {

    private static final Predicate<ResourceLocation> ALL = id -> true;

    private static ResourceLocation mc(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    private static ContainerContentsEntry entry(String path, int count, int weight) {
        return new ContainerContentsEntry(mc(path), count, weight);
    }

    private static List<String> ids(ContainerContentsPool pool) {
        return pool.entries().stream().map(e -> e.itemId().getPath()).toList();
    }

    @Test
    void everySwordGetsASpearTwinWithTheSameWeight() {
        ContainerContentsPool pool = new ContainerContentsPool(List.of(
                entry("bread", 4, 30), entry("wooden_sword", 1, 10), entry("iron_sword", 1, 2)), 2, 5);

        ContainerContentsPool out = SpearTwins.expand(pool, ALL);

        assertEquals(List.of("bread", "wooden_sword", "wooden_spear", "iron_sword", "iron_spear"), ids(out));
        assertEquals(10, out.entries().get(2).weight());
        assertEquals(2, out.entries().get(4).weight());
        assertEquals(pool.totalWeight() + 12, out.totalWeight());
        assertEquals(2, out.fillMin());
        assertEquals(5, out.fillMax());
    }

    @Test
    void twinCopiesTheSwordsRolls() {
        ContainerContentsEntry sword = new ContainerContentsEntry(mc("diamond_sword"), 2, 1, 7,
                false, 40, true, 80, ContainerContentsEntry.SLOT_AUTO, null,
                false, ContainerContentsEntry.DEFAULT_POTION_FORM, null, null, false, null);
        ContainerContentsEntry spear = SpearTwins.expand(new ContainerContentsPool(List.of(sword)), ALL)
                .entries().get(1);

        assertEquals(mc("diamond_spear"), spear.itemId());
        assertEquals(sword.count(), spear.count());
        assertEquals(sword.minCount(), spear.minCount());
        assertEquals(sword.randomDurability(), spear.randomDurability());
        assertEquals(sword.durabilityChance(), spear.durabilityChance());
        assertEquals(sword.randomEnchantment(), spear.randomEnchantment());
        assertEquals(sword.enchantmentChance(), spear.enchantmentChance());
    }

    @Test
    void unregisteredSpearsAddNothing() {
        ContainerContentsPool pool = new ContainerContentsPool(List.of(
                entry("stone_sword", 1, 5), entry("golden_sword", 1, 5)));
        Set<ResourceLocation> registered = Set.of(mc("golden_spear"));

        assertSame(pool, SpearTwins.expand(pool, id -> false));
        assertEquals(List.of("stone_sword", "golden_sword", "golden_spear"),
                ids(SpearTwins.expand(pool, registered::contains)));
    }

    @Test
    void expandingTwiceChangesNothing() {
        ContainerContentsPool once = SpearTwins.expand(new ContainerContentsPool(List.of(
                entry("netherite_sword", 1, 1), entry("arrow", 16, 4))), ALL);

        assertSame(once, SpearTwins.expand(once, ALL));
    }

    @Test
    void onlyVanillaTierSwordsHaveSpears() {
        assertEquals(mc("copper_spear"), SpearTwins.spearFor(mc("copper_sword")));
        assertNull(SpearTwins.spearFor(mc("sword")));
        assertNull(SpearTwins.spearFor(mc("amethyst_sword")));
        assertNull(SpearTwins.spearFor(ResourceLocation.fromNamespaceAndPath("deeperdarker", "warden_sword")));
        assertNull(SpearTwins.spearFor(mc("iron_axe")));
    }

    @Test
    void emptyAndNullPoolsPassThrough() {
        assertNull(SpearTwins.expand(null, ALL));
        ContainerContentsPool empty = ContainerContentsPool.empty();
        assertSame(empty, SpearTwins.expand(empty, ALL));
    }
}
