package games.brennan.dungeontrain.provenance;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Marking, claiming and reading an item's provenance, and the lines its Shift section shows. */
final class ItemProvenanceTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000005e");
    private static final long DAY = LocalDate.of(2026, 11, 11).toEpochDay();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("gear and rare vault finds are eligible; everything else is left alone")
    void eligibility() {
        for (var item : List.of(Items.DIAMOND_SWORD, Items.IRON_CHESTPLATE, Items.BOW, Items.SHIELD,
                Items.TRIDENT, Items.MACE, Items.ELYTRA, Items.WOODEN_HOE, Items.HEAVY_CORE, Items.ZOMBIE_SPAWN_EGG)) {
            assertTrue(ItemProvenance.eligible(new ItemStack(item)), item.toString());
        }
        for (var item : List.of(Items.COBBLESTONE, Items.WRITTEN_BOOK, Items.ARROW, Items.DIAMOND)) {
            ItemStack stack = new ItemStack(item);
            assertFalse(ItemProvenance.eligible(stack), item.toString());
            ItemProvenance.markFresh(stack, OptionalInt.of(4));
            assertFalse(stack.has(DataComponents.CUSTOM_DATA), "plain items stay unmarked so they keep stacking");
        }
    }

    @Test
    @DisplayName("gear baked into storage is marked new; plain items and held gear are left alone")
    void markFreshContents_marksOnlyUnclaimedGear() {
        ItemStack held = new ItemStack(Items.IRON_HELMET);
        ItemProvenance.claim(held, STEVE, "Steve", DAY, Optional.empty(), 0);
        SimpleContainer chest = new SimpleContainer(
            new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.COBBLESTONE, 12), held);

        assertTrue(ItemProvenance.markFreshContents(chest, OptionalInt.of(7)));
        assertEquals(OptionalInt.of(7), ItemProvenance.pIdx(chest.getItem(0)));
        assertFalse(chest.getItem(1).has(DataComponents.CUSTOM_DATA));
        assertEquals(ItemProvenance.How.OLD, ItemProvenance.view(chest.getItem(2)).orElseThrow().how());
        assertFalse(ItemProvenance.markFreshContents(chest, OptionalInt.of(7)), "a second pass changes nothing");

        assertTrue(ItemProvenance.claim(chest.getItem(0), STEVE, "Steve", DAY, Optional.empty(), 0));
        assertEquals(ItemProvenance.How.FOUND, ItemProvenance.view(chest.getItem(0)).orElseThrow().how());
    }

    @Test
    @DisplayName("a fresh carriage find is claimed as found, with its carriage")
    void freshFind_claimedAsFound() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        ItemProvenance.markFresh(sword, OptionalInt.of(20));
        assertEquals(OptionalInt.of(20), ItemProvenance.pIdx(sword));
        assertTrue(ItemProvenance.claim(sword, STEVE, "Steve", DAY, Optional.of("NOPI K 0C9-J"), 5));
        ItemProvenance.View v = ItemProvenance.view(sword).orElseThrow();
        assertEquals(ItemProvenance.How.FOUND, v.how());
        assertEquals(Optional.of(STEVE), v.who());
        assertEquals("Steve", v.name());
        assertEquals(DAY, v.day());
        assertEquals(Optional.of("NOPI K 0C9-J"), v.carriage());
    }

    @Test
    @DisplayName("the first holder sticks: later holders and marks change nothing")
    void claim_isWrittenOnce() {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        ItemProvenance.markFresh(sword, OptionalInt.empty());
        ItemProvenance.claim(sword, STEVE, "Steve", DAY, Optional.empty(), 0);
        assertFalse(ItemProvenance.claim(sword, UUID.randomUUID(), "Alex", DAY + 3, Optional.empty(), 0));
        ItemProvenance.markHow(sword, ItemProvenance.How.BOUGHT);
        ItemProvenance.View v = ItemProvenance.view(sword).orElseThrow();
        assertEquals("Steve", v.name());
        assertEquals(ItemProvenance.How.FOUND, v.how());
    }

    @Test
    @DisplayName("an unmarked item is old, with one of the old lines")
    void unmarked_isOld() {
        ItemStack axe = new ItemStack(Items.STONE_AXE);
        ItemProvenance.claim(axe, STEVE, "Steve", DAY, Optional.empty(), ItemProvenance.OLD_VARIANTS + 3);
        ItemProvenance.View v = ItemProvenance.view(axe).orElseThrow();
        assertEquals(ItemProvenance.How.OLD, v.how());
        assertEquals(3, v.variant());
        assertEquals("tooltip.dungeontrain.provenance.old.4", ProvenanceLines.oldKey(v.variant()));
    }

    @Test
    @DisplayName("made and bought are kept through the claim; an AIN credit drops the Made by line")
    void madeAndBought() {
        ItemStack made = new ItemStack(Items.IRON_CHESTPLATE);
        ItemProvenance.markHow(made, ItemProvenance.How.MADE);
        ItemProvenance.claim(made, STEVE, "Steve", DAY, Optional.empty(), 0);
        assertEquals(ItemProvenance.How.MADE, ItemProvenance.view(made).orElseThrow().how());
        assertEquals(List.of("tooltip.dungeontrain.provenance.made_by", "date"), keys(made));

        ItemProvenance.markAinCredited(made);
        assertEquals(List.of("date"), keys(made));

        ItemStack bought = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemProvenance.markHow(bought, ItemProvenance.How.BOUGHT);
        ItemProvenance.claim(bought, STEVE, "Steve", DAY, Optional.empty(), 0);
        assertEquals(List.of("tooltip.dungeontrain.provenance.bought_by", "date"), keys(bought));
    }

    @Test
    @DisplayName("lines run who, date, vault number, carriage")
    void lines_order() {
        ItemStack mace = new ItemStack(Items.MACE);
        ItemProvenance.markFresh(mace, OptionalInt.of(418));
        ItemProvenance.claim(mace, STEVE, "Steve", DAY, Optional.of("NOPI BM UC9-C"), 0);
        List<Component> lines = ProvenanceLines.of(ItemProvenance.view(mace), "Steve", Locale.UK,
            OptionalInt.of(3), List.of());
        assertEquals(4, lines.size());
        assertEquals("tooltip.dungeontrain.provenance.found_by", keyOf(lines.get(0)));
        assertEquals("11 Nov 2026", lines.get(1).getString());
        assertEquals("lore.dungeontrain.vault_find.serial", keyOf(lines.get(2)));
        assertEquals("NOPI BM UC9-C", lines.get(3).getString());
    }

    @Test
    @DisplayName("an old vault find's Pulled-by line moves into the data and both old lines go")
    void vaultLore_migrates() {
        ItemStack mace = new ItemStack(Items.MACE);
        mace.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("Heavy with old storms"),
            Component.translatable(ProvenanceSweep.LORE_SERIAL, 3),
            Component.translatable(ProvenanceSweep.LORE_PULLED_BY, "Steve", "2026-11-07"))));
        assertTrue(ProvenanceSweep.migrateVaultLore(mace));
        ItemProvenance.View v = ItemProvenance.view(mace).orElseThrow();
        assertEquals(ItemProvenance.How.FOUND, v.how());
        assertEquals("Steve", v.name());
        assertEquals(LocalDate.of(2026, 11, 7).toEpochDay(), v.day());
        assertEquals(Optional.empty(), v.who());
        assertEquals(List.of("Heavy with old storms"),
            mace.get(DataComponents.LORE).lines().stream().map(Component::getString).toList());
    }

    @Test
    @DisplayName("an item without the old lines is not migrated")
    void vaultLore_absent() {
        ItemStack mace = new ItemStack(Items.MACE);
        assertFalse(ProvenanceSweep.migrateVaultLore(mace));
        mace.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Hand Crafted by Alex"))));
        assertFalse(ProvenanceSweep.migrateVaultLore(mace));
        assertFalse(ItemProvenance.hasHolder(mace));
    }

    private static List<String> keys(ItemStack stack) {
        return ProvenanceLines.of(ItemProvenance.view(stack), "Steve", Locale.UK, OptionalInt.empty(), List.of())
            .stream().map(ItemProvenanceTest::keyOf).toList();
    }

    private static String keyOf(Component line) {
        return line.getContents() instanceof TranslatableContents t ? t.getKey() : "date";
    }
}
