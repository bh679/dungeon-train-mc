package games.brennan.dungeontrain.worldgen.density;

import com.mojang.datafixers.util.Pair;
import games.brennan.dungeontrain.worldgen.BackportBiomes;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context-free vanilla fallback: a marked overworld source with no published context must still
 * get a vanilla biome, never TerraBlender's pick (which can be Biomes O' Plenty).
 */
class OverworldStretchBiomesTest {

    private static final HolderOwner<Biome> OWNER = new HolderOwner<>() {};

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void vanillaTableIsTheOverworldPresetFilteredToMinecraft() {
        List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> table = OverworldStretchBiomes.vanillaTable().values();
        assertFalse(table.isEmpty());
        assertTrue(table.stream().allMatch(p -> "minecraft".equals(p.getSecond().location().getNamespace())));

        long expected = MultiNoiseBiomeSourceParameterList.knownPresets()
                .get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD).values().stream()
                .filter(p -> "minecraft".equals(p.getSecond().location().getNamespace()))
                .count();
        assertEquals(expected, table.size());
        assertSame(OverworldStretchBiomes.vanillaTable(), OverworldStretchBiomes.vanillaTable(), "cached");
    }

    @Test
    void holderTableKeepsThePointsAndFindsTheMappedEntry() {
        Climate.ParameterList<ResourceKey<Biome>> keyed = OverworldStretchBiomes.vanillaTable();
        java.util.Map<ResourceKey<Biome>, Holder<Biome>> holders = new java.util.HashMap<>();
        for (Pair<Climate.ParameterPoint, ResourceKey<Biome>> p : keyed.values()) {
            holders.computeIfAbsent(p.getSecond(), k -> Holder.Reference.createStandAlone(OWNER, k));
        }
        ResourceKey<Biome> dropped = keyed.values().get(3).getSecond();   // "missing from the registry" → null
        Climate.ParameterList<Holder<Biome>> table = OverworldStretchBiomes.withHolders(keyed,
                k -> k == dropped ? null : holders.get(k));

        assertEquals(keyed.values().size(), table.values().size());
        for (int i = 0; i < keyed.values().size(); i++) {
            assertSame(keyed.values().get(i).getFirst(), table.values().get(i).getFirst(), "same point, same order");
        }
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) {
            Climate.TargetPoint target = Climate.target(
                    rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1,
                    rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1);
            ResourceKey<Biome> key = keyed.findValue(target);
            Holder<Biome> expected = key == dropped ? null : holders.get(key);
            assertSame(expected, table.findValue(target));
        }
    }

    @Test
    void tableWithoutBackportHasNoBackportBiomesAndIsCached() {
        List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> reduced =
                OverworldStretchBiomes.vanillaTableWithoutBackport().values();
        assertTrue(reduced.stream().noneMatch(p -> BackportBiomes.OVERWORLD.contains(p.getSecond())));
        long expected = OverworldStretchBiomes.vanillaTable().values().stream()
                .filter(p -> !BackportBiomes.OVERWORLD.contains(p.getSecond())).count();
        assertEquals(expected, reduced.size());
        assertSame(OverworldStretchBiomes.vanillaTableWithoutBackport(),
                OverworldStretchBiomes.vanillaTableWithoutBackport(), "cached");
    }

    /**
     * Platform appends VanillaBackport's points after vanilla's; dropping them must give back the
     * pre-VanillaBackport pick everywhere. The base here is exact points (not vanilla's wide ranges, where a
     * planted point only ties), so each planted backport point strictly wins its own target.
     */
    @Test
    void droppingAppendedBackportPointsRestoresThePreBackportPick() {
        java.util.Random rnd = new java.util.Random(11);
        List<ResourceKey<Biome>> baseKeys = List.of(net.minecraft.world.level.biome.Biomes.PLAINS,
                net.minecraft.world.level.biome.Biomes.DARK_FOREST, net.minecraft.world.level.biome.Biomes.BIRCH_FOREST,
                net.minecraft.world.level.biome.Biomes.DRIPSTONE_CAVES);
        List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> base = new ArrayList<>();
        for (int i = 0; i < 64; i++) base.add(Pair.of(exactPoint(randomTarget(rnd)), baseKeys.get(i % baseKeys.size())));
        Climate.ParameterList<ResourceKey<Biome>> vanilla = new Climate.ParameterList<>(base);

        List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> appended = new ArrayList<>(base);
        List<Climate.TargetPoint> planted = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Climate.TargetPoint t = randomTarget(rnd);
            planted.add(t);
            appended.add(Pair.of(exactPoint(t), i % 2 == 0 ? BackportBiomes.PALE_GARDEN : BackportBiomes.SULFUR_CAVES));
        }
        Climate.ParameterList<ResourceKey<Biome>> withBackport = new Climate.ParameterList<>(appended);
        Climate.ParameterList<ResourceKey<Biome>> reduced = OverworldStretchBiomes.withoutBackport(withBackport);

        assertEquals(base.size(), reduced.values().size());
        for (int i = 0; i < base.size(); i++) {
            assertSame(base.get(i).getFirst(), reduced.values().get(i).getFirst(), "same point, same order");
        }
        for (Climate.TargetPoint t : planted) {
            assertTrue(BackportBiomes.OVERWORLD.contains(withBackport.findValue(t)), "the appended table picks it");
            assertEquals(vanilla.findValue(t), reduced.findValue(t), "the reduced table picks what vanilla did");
        }
        for (int i = 0; i < 2000; i++) {
            Climate.TargetPoint t = randomTarget(rnd);
            assertEquals(vanilla.findValue(t), reduced.findValue(t));
        }
    }

    private static Climate.ParameterPoint exactPoint(Climate.TargetPoint t) {
        return Climate.parameters(
                Climate.unquantizeCoord(t.temperature()), Climate.unquantizeCoord(t.humidity()),
                Climate.unquantizeCoord(t.continentalness()), Climate.unquantizeCoord(t.erosion()),
                Climate.unquantizeCoord(t.depth()), Climate.unquantizeCoord(t.weirdness()), 0f);
    }

    private static Climate.TargetPoint randomTarget(java.util.Random rnd) {
        return Climate.target(rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1,
                rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1);
    }

    @Test
    void fallbackPicksTheVanillaTableBiomeFromTheSourcesOwnHolders() {
        // A source like TerraBlender's: every vanilla biome plus a modded one.
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> points = new ArrayList<>();
        for (Pair<Climate.ParameterPoint, ResourceKey<Biome>> p : OverworldStretchBiomes.vanillaTable().values()) {
            points.add(Pair.of(p.getFirst(), Holder.Reference.createStandAlone(OWNER, p.getSecond())));
        }
        ResourceKey<Biome> modded = ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "test_biome"));
        points.add(Pair.of(points.get(0).getFirst(), Holder.Reference.createStandAlone(OWNER, modded)));
        MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(points));

        Climate.Sampler sampler = Climate.empty();
        for (int q = -8; q <= 8; q += 4) {
            ResourceKey<Biome> expected = OverworldStretchBiomes.vanillaTable().findValue(sampler.sample(q, 16, q));
            Holder<Biome> got = OverworldStretchBiomes.vanillaFallback(source, q, 16, q, sampler);
            assertEquals(expected, got.unwrapKey().orElseThrow());
            assertTrue(source.possibleBiomes().contains(got), "holder comes from the source itself");
        }
    }

    @Test
    void fallbackReturnsNullWhenTheSourceLacksTheBiome() {
        ResourceKey<Biome> modded = ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "only_biome"));
        MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(List.of(
                Pair.of(OverworldStretchBiomes.vanillaTable().values().get(0).getFirst(),
                        Holder.Reference.createStandAlone(OWNER, modded)))));
        assertNull(OverworldStretchBiomes.vanillaFallback(source, 0, 16, 0, Climate.empty()));
    }
}
