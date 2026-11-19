package games.brennan.dungeontrain.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Book loot entries with {@link BookEnchants}: exact stored enchantments (a Mending book) and books
 * that roll several different max-level, never-cursed enchantments, as the green vault hands out.
 * The full rich-book roll reads enchantment tags, which only a running server binds; that path is
 * checked in game.
 */
final class ContainerContentsEnchantsTest {

    private static final ResourceLocation BOOK = ResourceLocation.withDefaultNamespace("book");
    private static final ResourceLocation ENCHANTED_BOOK = ResourceLocation.withDefaultNamespace("enchanted_book");
    private static final ResourceLocation MENDING = ResourceLocation.withDefaultNamespace("mending");
    private static final ResourceLocation SHARPNESS = ResourceLocation.withDefaultNamespace("sharpness");
    private static final BlockPos CELL = new BlockPos(3, 0, 2);

    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
    }

    @Test
    @DisplayName("an entry keeps its book enchantments through every other edit, and an empty one is none")
    void entryCarriesEnchants() {
        BookEnchants rich = BookEnchants.random(1, 7);
        ContainerContentsEntry e = new ContainerContentsEntry(BOOK, 3, 974).withEnchants(rich);
        assertEquals(rich, e.withWeight(5).withCount(2).withMinCount(1).withRandomEnchantment(false)
            .withPotion(null).withBook(null).cyclePotionForm().enchants());
        assertNull(new ContainerContentsEntry(BOOK, 1, 1).enchants());
        assertNull(e.withEnchants(BookEnchants.random(0, 0)).enchants());
    }

    @Test
    @DisplayName("JSON round-trips both fields, and writes nothing for an entry without them")
    void jsonRoundTrip() {
        BookEnchants both = new BookEnchants(Map.of(MENDING, 1), 1, 7);
        StringBuilder sb = new StringBuilder("{ \"id\": \"minecraft:book\"");
        BookEnchantsCodec.appendJson(sb, both);
        sb.append(" }");
        JsonObject parsed = JsonParser.parseString(sb.toString()).getAsJsonObject();
        assertEquals(both, BookEnchantsCodec.fromJson(parsed));

        StringBuilder none = new StringBuilder();
        BookEnchantsCodec.appendJson(none, null);
        assertEquals("", none.toString());
        assertNull(BookEnchantsCodec.fromJson(JsonParser.parseString("{ \"id\": \"x\" }").getAsJsonObject()));
    }

    @Test
    @DisplayName("the menu's range: keeps exact enchants, starts at 1, caps at 16, turns the ordinary roll off; 0 clears it")
    void menuRange() {
        ContainerContentsEntry mending = new ContainerContentsEntry(ENCHANTED_BOOK, 1, 1)
            .withEnchants(BookEnchants.exact(Map.of(MENDING, 1)));
        ContainerContentsEntry ranged = ContainerContentsEnchants.withRandomRange(mending, 0, 99);
        assertEquals(new BookEnchants(Map.of(MENDING, 1), 1, BookEnchants.MAX_RANDOM), ranged.enchants());
        assertFalse(ranged.randomEnchantment());
        assertEquals(BookEnchants.exact(Map.of(MENDING, 1)),
            ContainerContentsEnchants.withRandomRange(ranged, 1, 0).enchants());

        ContainerContentsEntry plain = new ContainerContentsEntry(BOOK, 1, 1);
        assertEquals(BookEnchants.random(2, 5), ContainerContentsEnchants.withRandomRange(plain, 2, 5).enchants());
        assertNull(ContainerContentsEnchants.withRandomRange(
            ContainerContentsEnchants.withRandomRange(plain, 2, 5), 2, 0).enchants());

        ContainerContentsEntry apple = new ContainerContentsEntry(ResourceLocation.withDefaultNamespace("apple"), 1, 1);
        assertEquals(apple, ContainerContentsEnchants.withRandomRange(apple, 1, 7));
    }

    @Test
    @DisplayName("NBT round-trips for the clipboard")
    void nbtRoundTrip() {
        BookEnchants both = new BookEnchants(Map.of(MENDING, 1), 2, 5);
        assertEquals(both, BookEnchantsCodec.fromNbt(BookEnchantsCodec.toNbt(both)));
        BookEnchants full = new BookEnchants(Map.of(), 1, 7, 2, 4, Map.of(MENDING, true, SHARPNESS, false));
        assertEquals(full, BookEnchantsCodec.fromNbt(BookEnchantsCodec.toNbt(full)));
    }

    @Test
    @DisplayName("JSON round-trips the level range and the list; the defaults are not written")
    void jsonLevelsAndList() {
        BookEnchants full = new BookEnchants(Map.of(), 1, 7, 3, BookEnchants.LEVEL_TOP, Map.of(MENDING, true, SHARPNESS, false));
        StringBuilder sb = new StringBuilder("{ \"id\": \"minecraft:book\"");
        BookEnchantsCodec.appendJson(sb, full);
        sb.append(" }");
        assertEquals(full, BookEnchantsCodec.fromJson(JsonParser.parseString(sb.toString()).getAsJsonObject()));
        StringBuilder plain = new StringBuilder();
        BookEnchantsCodec.appendJson(plain, BookEnchants.random(1, 7));
        assertFalse(plain.toString().contains("level") || plain.toString().contains("list"), plain.toString());
    }

    @Test
    @DisplayName("level range: 0 means each enchantment's top level, and nothing rolls above its top")
    void levelRange() {
        BookEnchants top = BookEnchants.random(1, 7);
        assertEquals(5, top.levelLow(5));
        assertEquals(5, top.levelHigh(5));
        assertEquals("max", top.levelLabel());
        BookEnchants threeUp = top.withLevels(3, BookEnchants.LEVEL_TOP);
        assertEquals(3, threeUp.levelLow(5));
        assertEquals(5, threeUp.levelHigh(5));
        assertEquals(1, threeUp.levelLow(1), "Mending-like top 1 stays at 1");
        assertEquals("3-max", threeUp.levelLabel());
        BookEnchants twoFour = top.withLevels(2, 4);
        assertEquals(2, twoFour.levelLow(5));
        assertEquals(4, twoFour.levelHigh(5));
        assertEquals(2, twoFour.levelHigh(2));
        assertEquals("2-4", twoFour.levelLabel());
        assertEquals(4, top.withLevels(9, 4).levelMin(), "min above max is pulled down");
    }

    @Test
    @DisplayName("list: on rolls, off and undecided never do; a toggle records the whole list")
    void listRules() {
        HolderLookup.RegistryLookup<Enchantment> lookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> sharpness = lookup.getOrThrow(Enchantments.SHARPNESS);
        Holder<Enchantment> mending = lookup.getOrThrow(Enchantments.MENDING);
        Holder<Enchantment> smite = lookup.getOrThrow(Enchantments.SMITE);
        List<Holder<Enchantment>> candidates = List.of(mending, sharpness, smite);
        BookEnchants saved = BookEnchants.random(1, 7).withList(Map.of(SHARPNESS, true, MENDING, false));
        assertTrue(ContainerContentsEnchants.allowed(saved, sharpness));
        assertFalse(ContainerContentsEnchants.allowed(saved, mending));
        assertFalse(ContainerContentsEnchants.allowed(saved, smite), "undecided never rolls");
        assertEquals(List.of(ResourceLocation.withDefaultNamespace("smite")),
            ContainerContentsEnchants.undecided(saved, candidates));

        BookEnchants smiteOn = ContainerContentsEnchants.toggled(saved, ResourceLocation.withDefaultNamespace("smite"), candidates);
        assertTrue(ContainerContentsEnchants.allowed(smiteOn, smite));
        assertTrue(ContainerContentsEnchants.undecided(smiteOn, candidates).isEmpty());
        assertFalse(ContainerContentsEnchants.allowed(
            ContainerContentsEnchants.toggled(smiteOn, SHARPNESS, candidates), sharpness));

        BookEnchants allOff = ContainerContentsEnchants.allSet(smiteOn, false, candidates);
        for (Holder<Enchantment> h : candidates) assertFalse(ContainerContentsEnchants.allowed(allOff, h));
        assertTrue(ContainerContentsEnchants.undecided(allOff, candidates).isEmpty());
    }

    @Test
    @DisplayName("a Mending entry spawns an enchanted book with exactly Mending I")
    void mendingBook() {
        ContainerContentsEntry e = new ContainerContentsEntry(ENCHANTED_BOOK, 1, 1)
            .withRandomEnchantment(false).withEnchants(BookEnchants.exact(Map.of(MENDING, 1)));
        ItemStack book = ContainerContentsRoller.rollItemStack(e, 1, CELL, 42L, 0, 0, 0, registries);
        assertTrue(book.is(Items.ENCHANTED_BOOK));
        ItemEnchantments stored = book.get(DataComponents.STORED_ENCHANTMENTS);
        assertEquals(1, stored.size());
        Holder<Enchantment> mending = registries.lookupOrThrow(Registries.ENCHANTMENT)
            .getOrThrow(Enchantments.MENDING);
        assertEquals(1, stored.getLevel(mending));
    }

    @Test
    @DisplayName("rich-book picks: up to n different enchantments, all compatible, never one already held")
    void richBookPicks() {
        // The test registries have no tags bound, so stand in for #on_random_loot minus curses and
        // Mending by key; in game ContainerContentsEnchants.randomCandidates reads the tags.
        HolderLookup.RegistryLookup<Enchantment> lookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
        Set<ResourceKey<Enchantment>> excluded = Set.of(Enchantments.BINDING_CURSE, Enchantments.VANISHING_CURSE,
            Enchantments.MENDING);
        List<Holder<Enchantment>> candidates = lookup.listElements()
            .filter(h -> !excluded.contains(h.key()))
            .sorted(java.util.Comparator.comparing(h -> h.key().location()))
            .<Holder<Enchantment>>map(h -> h)
            .toList();
        Holder<Enchantment> mending = lookup.getOrThrow(Enchantments.MENDING);
        Set<Integer> sizesSeen = new HashSet<>();
        for (long seed = 1; seed <= 300; seed++) {
            RandomSource rng = RandomSource.create(seed);
            int n = 1 + rng.nextInt(7);
            List<Holder<Enchantment>> picked = ContainerContentsEnchants.pick(candidates, List.of(mending), n, rng);
            assertTrue(!picked.isEmpty() && picked.size() <= n, "picked " + picked.size() + " of " + n);
            assertEquals(picked.size(), new HashSet<>(picked).size(), "all different");
            assertFalse(picked.contains(mending), "never one already held");
            for (Holder<Enchantment> a : picked) {
                assertTrue(Enchantment.areCompatible(a, mending));
                for (Holder<Enchantment> b : picked) {
                    if (a != b) assertTrue(Enchantment.areCompatible(a, b), a + " vs " + b);
                }
            }
            sizesSeen.add(picked.size());
        }
        assertTrue(sizesSeen.contains(1) && sizesSeen.contains(7), "sizes " + sizesSeen);
        assertEquals(ContainerContentsEnchants.pick(candidates, List.of(), 5, RandomSource.create(9)),
            ContainerContentsEnchants.pick(candidates, List.of(), 5, RandomSource.create(9)), "same seed, same book");
    }

    @Test
    @DisplayName("the green vault's default pool carries the rich book and the Mending book")
    void greenVaultPool() {
        ContainerContentsPool green = VaultLoot.defaultPool(VaultLoot.GREEN_KEY);
        ContainerContentsEntry rich = green.entries().stream().filter(x -> x.itemId().equals(BOOK)).findFirst().orElseThrow();
        assertEquals(1, rich.enchants().randomMin());
        assertEquals(7, rich.enchants().randomMax());
        assertTrue(rich.enchants().isTopLevelOnly());
        Map<ResourceLocation, Boolean> list = rich.enchants().list();
        assertEquals(Boolean.FALSE, list.get(MENDING));
        assertEquals(Boolean.FALSE, list.get(ResourceLocation.withDefaultNamespace("binding_curse")));
        assertEquals(Boolean.FALSE, list.get(ResourceLocation.withDefaultNamespace("vanishing_curse")));
        assertEquals(Boolean.TRUE, list.get(ResourceLocation.withDefaultNamespace("sharpness")));
        assertEquals(1, rich.minCount());
        assertEquals(3, rich.count());
        ContainerContentsEntry mending = green.entries().stream()
            .filter(x -> x.itemId().equals(ENCHANTED_BOOK)).findFirst().orElseThrow();
        assertEquals(BookEnchants.exact(Map.of(MENDING, 1)), mending.enchants());
        assertEquals(243, mending.weight());
    }
}
