package games.brennan.dungeontrain.tunnel;

import games.brennan.dungeontrain.editor.TrackVariantGroupStore;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.template.TemplateGroup;
import games.brennan.dungeontrain.template.TemplateMeta;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rolling a tunnel's group, and drawing its templates from that group. */
final class TunnelGroupRollTest {

    private static final TrackKind SECTION = TrackKind.TUNNEL_SECTION;
    private static final TrackKind PORTAL = TrackKind.TUNNEL_PORTAL;
    private static final GateContext OVERWORLD = new GateContext(1, TrainPhase.OVERWORLD);
    private static final TemplateGate NETHER_ONLY = new TemplateGate(0, TemplateGate.ALL, EnumSet.of(TrainPhase.NETHER));

    @BeforeEach
    @AfterEach
    void cleanSlate() {
        TrackVariantRegistry.clear();
        TrackVariantGroupStore.clearCache();
        TrackVariantWeights.clear();
        TunnelGroupStore.injectForTesting(null);
    }

    private static void template(TrackKind kind, String name, String... groups) {
        TrackVariantRegistry.register(kind, name);
        TrackVariantWeights.injectForTesting(kind, name, TemplateMeta.of(1).withGroups(List.of(groups)));
    }

    /** Stone and brick sets; {@code s_both} serves both; the ungrouped defaults weigh 0. */
    private static void stoneAndBrick() {
        template(SECTION, "s_stone", "stone");
        template(SECTION, "s_brick", "brick");
        template(SECTION, "s_both", "stone", "brick");
        template(PORTAL, "p_stone", "stone");
        template(PORTAL, "p_brick", "brick");
        TunnelGroupStore.injectForTesting(TunnelGroupStore.Registry.ofWeights(Map.of("stone", 1, "brick", 1), 0));
    }

    private static Set<TemplateGroup> rolledOver(int keys, GateContext ctx) {
        Set<TemplateGroup> seen = new HashSet<>();
        for (long k = 0; k < keys; k++) seen.add(TunnelGroupRoll.roll(99L, k, ctx));
        return seen;
    }

    private static Set<String> pickedOver(TrackKind kind, TemplateGroup group, GateContext ctx) {
        Set<String> seen = new HashSet<>();
        for (int tile = 0; tile < 300; tile++) seen.add(TrackVariantRegistry.pickName(kind, 7L, tile, ctx, group));
        return seen;
    }

    @Test
    @DisplayName("tunnels roll every weighted group, and never the zero-weight ungrouped pool")
    void rollsByWeight() {
        stoneAndBrick();
        assertEquals(Set.of(TemplateGroup.of("stone"), TemplateGroup.of("brick")), rolledOver(200, OVERWORLD));
    }

    @Test
    @DisplayName("the same key always rolls the same group")
    void deterministic() {
        stoneAndBrick();
        for (long k = 0; k < 50; k++) {
            assertEquals(TunnelGroupRoll.roll(5L, k, OVERWORLD), TunnelGroupRoll.roll(5L, k, OVERWORLD));
        }
    }

    @Test
    @DisplayName("a group whose only entrance is gated out here cannot be rolled here")
    void gatedGroupSkipped() {
        stoneAndBrick();
        TrackVariantWeights.injectForTesting(PORTAL, "p_brick",
            new TemplateMeta(1, NETHER_ONLY).withGroups(List.of("brick")));
        assertEquals(Set.of(TemplateGroup.of("stone")), rolledOver(200, OVERWORLD));
    }

    @Test
    @DisplayName("a template in two groups is drawn for both; the draw stays inside the group")
    void drawsInsideGroup() {
        stoneAndBrick();
        assertEquals(Set.of("s_stone", "s_both"), pickedOver(SECTION, TemplateGroup.of("stone"), OVERWORLD));
        assertEquals(Set.of("s_brick", "s_both"), pickedOver(SECTION, TemplateGroup.of("brick"), OVERWORLD));
        assertEquals(Set.of("p_stone"), pickedOver(PORTAL, TemplateGroup.of("stone"), OVERWORLD));
    }

    @Test
    @DisplayName("a group with no member allowed here falls back to the whole gated pool")
    void emptyGroupFallsBack() {
        stoneAndBrick();
        Set<String> picked = pickedOver(PORTAL, TemplateGroup.of("missing"), OVERWORLD);
        assertTrue(picked.contains("p_stone") && picked.contains("p_brick"));
    }

    @Test
    @DisplayName("with nothing grouped, the ungrouped roll picks exactly what the old pick did")
    void noGroupsIsUnchanged() {
        TrackVariantRegistry.register(SECTION, "a");
        TrackVariantRegistry.register(SECTION, "b");
        TrackVariantRegistry.register(PORTAL, "c");
        TemplateGroup rolled = TunnelGroupRoll.roll(3L, 42L, OVERWORLD);
        assertEquals(TemplateGroup.UNGROUPED, rolled);
        for (int tile = 0; tile < 100; tile++) {
            assertEquals(TrackVariantRegistry.pickName(SECTION, 3L, tile, OVERWORLD),
                TrackVariantRegistry.pickName(SECTION, 3L, tile, OVERWORLD, rolled));
        }
    }

    @Test
    @DisplayName("every weight at zero means no group filter at all")
    void allZeroIsNoFilter() {
        stoneAndBrick();
        TunnelGroupStore.injectForTesting(TunnelGroupStore.Registry.ofWeights(Map.of("stone", 0, "brick", 0), 0));
        assertNull(TunnelGroupRoll.roll(1L, 1L, OVERWORLD));
    }

    @Test
    @DisplayName("the shipped stone/blackstone split still fades Overworld tunnels into the Nether look")
    void netherFadeSurvivesGroups() {
        // The bundled setup: default in 'stone' (never the Nether), the dark pair in 'blackstone' (Nether only).
        TemplateGate notNether = new TemplateGate(0, TemplateGate.ALL,
            EnumSet.complementOf(EnumSet.of(TrainPhase.NETHER)));
        for (TrackKind kind : List.of(SECTION, PORTAL)) {
            TrackVariantWeights.injectForTesting(kind, TrackKind.DEFAULT_NAME,
                new TemplateMeta(1, notNether).withGroups(List.of("stone")));
        }
        TrackVariantRegistry.register(SECTION, "darktunnel");
        TrackVariantRegistry.register(PORTAL, "darkportal");
        TrackVariantWeights.injectForTesting(SECTION, "darktunnel", new TemplateMeta(1, NETHER_ONLY).withGroups(List.of("blackstone")));
        TrackVariantWeights.injectForTesting(PORTAL, "darkportal", new TemplateMeta(1, NETHER_ONLY).withGroups(List.of("blackstone")));
        GateContext nether = new GateContext(1, TrainPhase.NETHER);

        // An Overworld tunnel can only roll 'stone'; a Nether one only 'blackstone'.
        assertEquals(Set.of(TemplateGroup.of("stone")), rolledOver(100, OVERWORLD));
        assertEquals(Set.of(TemplateGroup.of("blackstone")), rolledOver(100, nether));
        // In the crossfade a 'stone' tunnel's Nether overlay has no 'stone' member allowed in the Nether,
        // so it falls back to the dark template — the Overworld → Nether fade still happens.
        assertEquals(Set.of("darktunnel"), pickedOver(SECTION, TemplateGroup.of("stone"), nether));
        assertEquals(Set.of("darkportal"), pickedOver(PORTAL, TemplateGroup.of("stone"), nether));
        assertEquals(Set.of(TrackKind.DEFAULT_NAME), pickedOver(SECTION, TemplateGroup.of("stone"), OVERWORLD));
    }

    @Test
    @DisplayName("a test tunnel rolls only among the tested template's groups, preferring buildable ones")
    void rollAmongTestedGroups() {
        stoneAndBrick();
        template(SECTION, "s_mossy", "mossy");   // no mossy entrance: cannot build
        for (long k = 0; k < 50; k++) {
            assertEquals(TemplateGroup.of("stone"),
                TunnelGroupRoll.rollAmong(3L, k, OVERWORLD, List.of("mossy", "stone")));
        }
        Set<TemplateGroup> both = new HashSet<>();
        for (long k = 0; k < 200; k++) both.add(TunnelGroupRoll.rollAmong(3L, k, OVERWORLD, List.of("brick", "stone")));
        assertEquals(Set.of(TemplateGroup.of("stone"), TemplateGroup.of("brick")), both);
        assertEquals(TemplateGroup.of("mossy"), TunnelGroupRoll.rollAmong(3L, 1L, OVERWORLD, List.of("mossy")));
        assertEquals(TemplateGroup.UNGROUPED, TunnelGroupRoll.rollAmong(3L, 1L, OVERWORLD, List.of()));
    }

    @Test
    @DisplayName("inside a group each member draws at its weight in that group")
    void memberWeightsInsideAGroup() {
        stoneAndBrick();
        // s_both weighs 0 in stone but keeps its template weight in brick.
        TrackVariantWeights.injectForTesting(SECTION, "s_both",
            TemplateMeta.of(1).withGroups(List.of("stone", "brick")).withGroupWeight("stone", 0));
        assertEquals(Set.of("s_stone"), pickedOver(SECTION, TemplateGroup.of("stone"), OVERWORLD));
        assertEquals(Set.of("s_brick", "s_both"), pickedOver(SECTION, TemplateGroup.of("brick"), OVERWORLD));
    }

    @Test
    @DisplayName("the roster snapshot lists every group's members at their in-group weight")
    void snapshotMembers() {
        stoneAndBrick();
        TrackVariantWeights.injectForTesting(SECTION, "s_both",
            TemplateMeta.of(1).withGroups(List.of("stone", "brick")).withGroupWeight("stone", 6));
        var snap = TunnelGroupEditing.snapshot();
        assertTrue(snap.membersOf("stone", "tunnel_section").stream()
            .anyMatch(m -> m.name().equals("s_both") && m.weight() == 6));
        assertTrue(snap.membersOf("brick", "tunnel_section").stream()
            .anyMatch(m -> m.name().equals("s_both") && m.weight() == 1));
        // The synthetic default is ungrouped, so it is the Ungrouped pool's member.
        assertTrue(snap.membersOf("", "tunnel_section").stream()
            .anyMatch(m -> m.name().equals(TrackKind.DEFAULT_NAME)));
    }

    @Test
    @DisplayName("'ungrouped' is reserved for the ungrouped pool")
    void ungroupedIsReserved() {
        assertNull(TunnelGroupEditing.parseId("ungrouped"));
        assertEquals("stone", TunnelGroupEditing.parseId("Stone"));
    }

    @Test
    @DisplayName("a group whose own gate excludes the spot is never rolled there")
    void groupGateExcludes() {
        stoneAndBrick();
        TunnelGroupStore.injectForTesting(new TunnelGroupStore.Registry(Map.of(
            "stone", new TemplateMeta(1, NETHER_ONLY),
            "brick", TemplateMeta.of(1)), 0));
        assertEquals(Set.of(TemplateGroup.of("brick")), rolledOver(200, OVERWORLD));
        // Level bounds count too: brick from level 5 up leaves nothing at level 1.
        TunnelGroupStore.injectForTesting(new TunnelGroupStore.Registry(Map.of(
            "stone", new TemplateMeta(1, NETHER_ONLY),
            "brick", new TemplateMeta(1, new TemplateGate(5, TemplateGate.ALL, null))), 0));
        assertNull(TunnelGroupRoll.roll(1L, 1L, OVERWORLD));
    }

    @Test
    @DisplayName("groups.json keeps bare weights for ungated groups and objects for gated ones")
    void registryGateJson() {
        TunnelGroupStore.Registry r = new TunnelGroupStore.Registry(Map.of(
            "stone", TemplateMeta.of(3),
            "dark", new TemplateMeta(2, NETHER_ONLY),
            "deep", new TemplateMeta(1, TemplateGate.DEFAULT, "deep_stage")), 1);
        com.google.gson.JsonObject json = TunnelGroupStore.toJson(r);
        assertTrue(json.getAsJsonObject("groups").get("stone").isJsonPrimitive());
        assertTrue(json.getAsJsonObject("groups").get("dark").isJsonObject());
        assertEquals(r, TunnelGroupStore.fromJson(json));
        // A file from before groups had gates reads as plain weights.
        TunnelGroupStore.Registry old = TunnelGroupStore.fromJson(com.google.gson.JsonParser.parseString(
            "{\"groups\":{\"stone\":4},\"ungroupedWeight\":2}"));
        assertEquals(4, old.weightOf("stone"));
        assertTrue(old.gateOf("stone").isDefault());
    }

    @Test
    @DisplayName("the registry round-trips through groups.json")
    void registryJson() {
        TunnelGroupStore.Registry r = TunnelGroupStore.Registry.ofWeights(Map.of("stone", 3, "brick", 1), 2);
        assertEquals(r, TunnelGroupStore.fromJson(TunnelGroupStore.toJson(r)));
    }
}
