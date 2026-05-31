package games.brennan.dungeontrain.tunnel;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link TunnelPalette#isUndergroundMaterial} — the per-block
 * predicate that gates tunnel section / down-stairs qualification at
 * worldgen time. The predicate has to recognise natural bulk terrain in
 * all three vanilla dimensions; this test pins the accepted set per
 * dimension and the rejected set (player variants, fluids, surface
 * features).
 *
 * <p>Requires {@code unitTest.enable()} in build.gradle so the NeoForge
 * moddev runtime bootstraps {@code Blocks.*} registry singletons before
 * the test class loads — without that, {@code Blocks.NETHERRACK} would
 * resolve to {@code null} and these tests would throw at class init.
 * Mirrors {@code FallingBlockAnchorTest}.</p>
 *
 * <p><b>Tag-backed branches are NOT tested here.</b> {@code BlockState.is(BlockTags.X)}
 * relies on tags loaded from datapacks at server start, which the moddev
 * unit-test runtime does not bootstrap. Stone, deepslate, netherrack,
 * basalt, crimson_nylium, etc. all flow through tags
 * ({@code BASE_STONE_OVERWORLD}, {@code BASE_STONE_NETHER}, {@code NYLIUM},
 * {@code WART_BLOCKS}, {@code DIRT}, ore-replaceable tags) and therefore
 * report {@code false} in this runtime. Their in-game behaviour is
 * verified at Gate 2 by world-walking a fresh nether/end Dungeon Train
 * world — tunnels appearing inside solid terrain proves the tag path.
 * The assertions below cover only the direct {@code s.is(Blocks.X)}
 * per-block branches, which DO work in the unit-test runtime.</p>
 */
final class TunnelPaletteTest {

    /**
     * Overworld accepts — direct {@code Blocks.X} matches only. Stone /
     * deepslate / granite / dirt / coal_ore go through {@code BlockTags}
     * and cannot be unit-tested here (see class javadoc).
     */
    static Stream<Block> overworldAccepts() {
        return Stream.of(
            Blocks.TUFF,
            Blocks.GRAVEL,
            Blocks.SANDSTONE,
            Blocks.RED_SANDSTONE,
            Blocks.CLAY,
            Blocks.TERRACOTTA,
            Blocks.WHITE_TERRACOTTA,
            Blocks.ORANGE_TERRACOTTA,
            Blocks.RED_SAND,
            Blocks.MUD,
            Blocks.PACKED_MUD
        );
    }

    /**
     * Nether accepts — direct {@code Blocks.X} matches only. Netherrack /
     * basalt / blackstone / nyliums / wart blocks go through {@code BlockTags}
     * and cannot be unit-tested here (see class javadoc).
     */
    static Stream<Block> netherAccepts() {
        return Stream.of(
            Blocks.SMOOTH_BASALT,
            Blocks.GILDED_BLACKSTONE,
            Blocks.MAGMA_BLOCK,
            Blocks.SOUL_SAND,
            Blocks.SOUL_SOIL,
            Blocks.GLOWSTONE,
            Blocks.SHROOMLIGHT,
            Blocks.NETHER_GOLD_ORE,
            Blocks.NETHER_QUARTZ_ORE,
            Blocks.ANCIENT_DEBRIS
        );
    }

    static Stream<Block> endAccepts() {
        return Stream.of(
            Blocks.END_STONE
        );
    }

    static Stream<Block> rejects() {
        return Stream.of(
            Blocks.END_STONE_BRICKS,   // player-crafted variant, not natural terrain
            Blocks.END_PORTAL_FRAME,   // structure block
            Blocks.BEDROCK,            // build-limit floor / nether bedrock cap
            Blocks.AIR,                // open space
            Blocks.OAK_LOG,            // tree trunk — surface feature
            Blocks.OAK_LEAVES,         // canopy
            Blocks.NETHER_BRICKS,      // player / nether-fortress variant, not raw terrain
            Blocks.PURPUR_BLOCK,       // end-city structure block
            Blocks.OBSIDIAN            // bulk obsidian (lava-source remnants, nether roof) — kept out so tunnel won't qualify inside lava-formed walls
        );
    }

    static Stream<Block> fluids() {
        return Stream.of(
            Blocks.WATER,
            Blocks.LAVA
        );
    }

    @ParameterizedTest
    @MethodSource("overworldAccepts")
    @DisplayName("overworld terrain — accepted (regression guard)")
    void overworldTerrainAccepted(Block block) {
        assertTrue(
            TunnelPalette.isUndergroundMaterial(block.defaultBlockState()),
            () -> block + " must qualify as overworld underground material"
        );
    }

    @ParameterizedTest
    @MethodSource("netherAccepts")
    @DisplayName("nether terrain — accepted")
    void netherTerrainAccepted(Block block) {
        assertTrue(
            TunnelPalette.isUndergroundMaterial(block.defaultBlockState()),
            () -> block + " must qualify as nether underground material"
        );
    }

    @ParameterizedTest
    @MethodSource("endAccepts")
    @DisplayName("end terrain — accepted")
    void endTerrainAccepted(Block block) {
        assertTrue(
            TunnelPalette.isUndergroundMaterial(block.defaultBlockState()),
            () -> block + " must qualify as end underground material"
        );
    }

    @ParameterizedTest
    @MethodSource("rejects")
    @DisplayName("non-terrain blocks — rejected")
    void nonTerrainRejected(Block block) {
        assertFalse(
            TunnelPalette.isUndergroundMaterial(block.defaultBlockState()),
            () -> block + " must NOT qualify as underground material"
        );
    }

    @ParameterizedTest
    @MethodSource("fluids")
    @DisplayName("fluid source blocks — rejected via FluidState guard")
    void fluidsRejected(Block block) {
        assertFalse(
            TunnelPalette.isUndergroundMaterial(block.defaultBlockState()),
            () -> block + " must be rejected by the FluidState guard"
        );
    }
}
