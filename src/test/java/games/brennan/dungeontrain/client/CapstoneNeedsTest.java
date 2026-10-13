package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.advancement.TabGateways;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.critereon.ImpossibleTrigger;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapstoneNeedsTest {

    private static final String DT = "dungeontrain:dungeon_train/";
    private static AdvancementTree tree;
    private static TabGateways.Layout layout;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<AdvancementHolder> all = new ArrayList<>();
        add(all, DT + "root", null);
        add(all, DT + "completionist", DT + "root");
        add(all, DT + "carts_100", DT + "root");
        add(all, DT + "train_explored", DT + "carts_100");
        add(all, DT + "tab_train_explorer", null);
        add(all, DT + "reached_nether", DT + "tab_train_explorer");
        add(all, DT + "train_explored_tab", DT + "tab_train_explorer");
        add(all, DT + "te_probe", DT + "tab_train_explorer"); // no burrito setting in the editor: the tab rule decides
        tree = new AdvancementTree();
        tree.addAll(all);
        layout = new TabGateways.Layout(
            Map.of(DT + "tab_train_explorer", DT + "carts_100", DT + "train_explored_tab", DT + "train_explored"),
            Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of(DT + "train_explored", DT + "tab_train_explorer"));
    }

    private static void add(List<AdvancementHolder> all, String id, String parent) {
        Advancement.Builder b = Advancement.Builder.advancement()
            .display(Items.MINECART, Component.literal(id), Component.literal(""), null, AdvancementType.TASK, true, true, false)
            .addCriterion("c", new net.minecraft.advancements.Criterion<>(new ImpossibleTrigger(), new ImpossibleTrigger.TriggerInstance()));
        if (parent != null) b.parent(ResourceLocation.parse(parent));
        all.add(b.build(ResourceLocation.parse(id)));
    }

    private static AdvancementNode node(String id) {
        return tree.get(ResourceLocation.parse(id));
    }

    @Test
    @DisplayName("a tab-complete copy stands for its original; anything else is itself")
    void copyStandsForOriginal() {
        assertEquals(ResourceLocation.parse(DT + "train_explored"),
            CapstoneNeeds.capstoneOf(layout, ResourceLocation.parse(DT + "train_explored_tab")));
        assertEquals(ResourceLocation.parse(DT + "tab_train_explorer"),
            CapstoneNeeds.capstoneOf(layout, ResourceLocation.parse(DT + "tab_train_explorer")), "a tab head copy is not a capstone");
    }

    @Test
    @DisplayName("a tab-complete advancement needs its whole tab, less its own copy")
    void tabCompleteNeedsItsTab() {
        ResourceLocation explored = ResourceLocation.parse(DT + "train_explored");
        assertTrue(CapstoneNeeds.needs(layout, explored, node(DT + "reached_nether")));
        assertTrue(CapstoneNeeds.needs(layout, explored, node(DT + "tab_train_explorer")));
        assertFalse(CapstoneNeeds.needs(layout, explored, node(DT + "train_explored_tab")), "never its own copy");
        assertFalse(CapstoneNeeds.needs(layout, explored, node(DT + "carts_100")), "another tab");
    }

    @Test
    @DisplayName("the burrito needs Dungeon Train-tab advancements only; ordinary advancements need nothing")
    void burritoNeedsTheDungeonTrainTab() {
        ResourceLocation burrito = ResourceLocation.parse(DT + "completionist");
        assertTrue(CapstoneNeeds.needs(layout, burrito, node(DT + "carts_100")));
        assertFalse(CapstoneNeeds.needs(layout, burrito, node(DT + "te_probe")), "Train Explorer counts through Explored by default");
        assertFalse(CapstoneNeeds.needs(layout, burrito, node(DT + "completionist")), "never itself");
        assertFalse(CapstoneNeeds.needs(layout, ResourceLocation.parse(DT + "carts_100"), node(DT + "root")), "not a capstone");
    }

    @Test
    @DisplayName("clicking a capstone pins it, clicking it again unpins, any other advancement clears the pin")
    void pinToggles() {
        ResourceLocation burrito = ResourceLocation.parse(DT + "completionist");
        ResourceLocation explored = ResourceLocation.parse(DT + "train_explored");
        assertEquals(burrito, CapstoneNeeds.nextPin(layout, null, burrito));
        assertEquals(null, CapstoneNeeds.nextPin(layout, burrito, burrito), "same one again: off");
        assertEquals(explored, CapstoneNeeds.nextPin(layout, burrito, ResourceLocation.parse(DT + "train_explored_tab")),
            "the in-tab copy pins its original");
        assertEquals(null, CapstoneNeeds.nextPin(layout, explored, ResourceLocation.parse(DT + "carts_100")), "another advancement clears it");
        assertTrue(CapstoneNeeds.isCapstone(layout, burrito));
        assertFalse(CapstoneNeeds.isCapstone(layout, ResourceLocation.parse(DT + "carts_100")));
    }

    @Test
    @DisplayName("a needs entry (All at Once) pins and highlights exactly the rules it lists")
    void needsListHighlightsItsRules() {
        TabGateways.Layout rules = new TabGateways.Layout(Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), Map.of(), Map.of(DT + "reached_nether", List.of(DT + "carts_100", DT + "te_probe")));
        ResourceLocation allAtOnce = ResourceLocation.parse(DT + "reached_nether");
        assertTrue(CapstoneNeeds.isCapstone(rules, allAtOnce));
        assertTrue(CapstoneNeeds.combinesRules(rules, allAtOnce));
        assertEquals(allAtOnce, CapstoneNeeds.nextPin(rules, null, allAtOnce));
        assertTrue(CapstoneNeeds.needs(rules, allAtOnce, node(DT + "carts_100")));
        assertTrue(CapstoneNeeds.needs(rules, allAtOnce, node(DT + "te_probe")), "across tabs too");
        assertFalse(CapstoneNeeds.needs(rules, allAtOnce, node(DT + "root")), "not listed");
        assertFalse(CapstoneNeeds.needs(rules, allAtOnce, node(DT + "reached_nether")), "never itself");
        assertFalse(CapstoneNeeds.combinesRules(layout, allAtOnce), "only when listed");
    }

    @Test
    @DisplayName("the shipped tabs file lists All at Once's and The Challenge's rules")
    void shippedNeedsTable() throws java.io.IOException {
        try (var in = java.nio.file.Files.newBufferedReader(
                games.brennan.dungeontrain.RepoPaths.resources().resolve("dungeontrain/advancement_tabs.json"))) {
            Map<String, List<String>> needs = TabGateways.Layout.parse(in).needs();
            List<String> rules = needs.get(DT + "all_at_once");
            assertEquals(14, rules.size());
            assertEquals(rules, needs.get(DT + "the_challenge"));
            assertTrue(rules.containsAll(List.of(DT + "self_medicated", DT + "pacifist_1000", DT + "contained_loop",
                DT + "no_break_1000", DT + "no_container_1000", DT + "three_melons", DT + "naked_and_unafraid")));
            for (String excluded : List.of("chests_100_unique", "leather_over_diamond", "challenge_complete_tab")) {
                assertFalse(rules.contains(DT + excluded), excluded);
            }
        }
    }
}
