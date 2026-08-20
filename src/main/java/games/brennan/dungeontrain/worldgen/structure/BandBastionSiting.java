package games.brennan.dungeontrain.worldgen.structure;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.mixin.JigsawStructureAccessor;
import games.brennan.dungeontrain.util.LogFirstN;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Where a bastion remnant stands in the band. The assembly is vanilla's — {@link JigsawPlacement#addPieces}
 * against vanilla's {@code minecraft:bastion/starts} pool — so the band gets the real housing units, hoglin
 * stables, treasure rooms and bridges, with their piglin brutes, Pigstep disc and netherite upgrade
 * template.
 *
 * <p>The one substitution is how {@code start_height} is read. Vanilla reads its roll as an absolute world
 * Y; the band reads the same number as a <b>Nether-space</b> Y and translates it onto track level, exactly
 * as the core terrain translates the Nether it samples. Same draw, same random, same order — so a seed
 * produces the same bastion at the same height relative to the Nether it came from.</p>
 *
 * <p>No heightmap projection is applied, matching vanilla's bastion: they sit at the height they roll,
 * embedded in whatever netherrack is there and open to the air where there is none.</p>
 */
public final class BandBastionSiting {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN SITE_ERRORS = new LogFirstN(5);

    private BandBastionSiting() {}

    /**
     * The band's generation point for a bastion, or {@link Optional#empty()} to let vanilla's own siting
     * run — which is what happens everywhere outside the band core, including the real Nether.
     */
    public static Optional<Structure.GenerationStub> site(JigsawStructure structure,
                                                          Structure.GenerationContext context,
                                                          HolderSet<Biome> biomes) {
        try {
            // JigsawStructure is final, so the accessor cast has to go via Object.
            JigsawStructureAccessor config = (JigsawStructureAccessor) (Object) structure;
            int maxDistanceFromCenter = config.dungeontrain$maxDistanceFromCenter();

            BandNetherStructures.Site band = BandNetherStructures.open(context, maxDistanceFromCenter);
            if (band == null) return Optional.empty();

            ChunkPos chunkPos = context.chunkPos();
            if (!band.biomeAllows(biomes, chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ())) {
                return Optional.empty();
            }

            int netherY = config.dungeontrain$startHeight().sample(context.random(),
                    new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor()));
            BlockPos start = new BlockPos(chunkPos.getMinBlockX(), band.bandYInCore(netherY),
                    chunkPos.getMinBlockZ());

            return JigsawPlacement.addPieces(
                    context,
                    config.dungeontrain$startPool(),
                    Optional.empty(),
                    config.dungeontrain$maxDepth(),
                    start,
                    config.dungeontrain$useExpansionHack(),
                    Optional.empty(),          // no heightmap projection — as vanilla's bastion
                    maxDistanceFromCenter,
                    PoolAliasLookup.create(List.of(), start, context.seed()),
                    DimensionPadding.ZERO,
                    LiquidSettings.APPLY_WATERLOGGING);
        } catch (Throwable t) {
            SITE_ERRORS.error(LOGGER, "[DungeonTrain] Bastion siting failed; leaving it to vanilla", t);
            return Optional.empty();
        }
    }
}
