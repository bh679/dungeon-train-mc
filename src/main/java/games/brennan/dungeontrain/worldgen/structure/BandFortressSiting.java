package games.brennan.dungeontrain.worldgen.structure;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.util.LogFirstN;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Where a Nether fortress stands in the band, and nothing else — the pieces are
 * {@link NetherFortressPieces}' own, exactly as {@code minecraft:fortress} builds them.
 *
 * <p>Only the <b>Y</b> differs, and only because it has to. Vanilla drops a fortress at a flat Nether-space
 * Y 64 and clamps its pieces into Nether-space 48–70; the band lives in the overworld, where those world Ys
 * describe ordinary terrain. {@link BandNetherStructures.Site#bandYInCore} translates each of them onto
 * track level by the same {@code bedY} ↔ Nether-centre mapping the core terrain itself uses, so a fortress
 * lands in exactly the slice of Nether the band stamped there.</p>
 *
 * <p>The legs come free: the band places its structure pieces <em>after</em> the core fill (see
 * {@code NetherStructuresFeature}), so vanilla's {@code fillColumnDown} walks down through real Nether air
 * and lava and stops on real netherrack, building the same supports it would in the Nether.</p>
 *
 * <p>Where a fortress straddles the train's corridor the track is carved straight through it — {@code
 * track_bed} runs last and wins, by design. Riding through a fortress is the point.</p>
 */
public final class BandFortressSiting {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN SITE_ERRORS = new LogFirstN(5);

    /** Vanilla's Nether-space start height, and the window it clamps its pieces into. */
    private static final int START_NETHER_Y = 64;
    private static final int PIECE_NETHER_Y_MIN = 48;
    private static final int PIECE_NETHER_Y_MAX = 70;

    /** Blocks a fortress can sprawl past its start chunk — its bridges run a long way. */
    private static final int FOOTPRINT_RADIUS = 80;

    private BandFortressSiting() {}

    /**
     * The band's generation point for a fortress, or {@link Optional#empty()} to let vanilla's own siting
     * run — which is what happens everywhere outside the band core, including the real Nether.
     */
    public static Optional<Structure.GenerationStub> site(Structure.GenerationContext context,
                                                          HolderSet<Biome> biomes) {
        try {
            BandNetherStructures.Site band = BandNetherStructures.open(context, FOOTPRINT_RADIUS);
            if (band == null) return Optional.empty();

            ChunkPos chunkPos = context.chunkPos();
            if (!band.biomeAllows(biomes, chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ())) {
                return Optional.empty();
            }

            BlockPos start = new BlockPos(chunkPos.getMinBlockX(), band.bandYInCore(START_NETHER_Y),
                    chunkPos.getMinBlockZ());
            return Optional.of(new Structure.GenerationStub(start,
                    builder -> generatePieces(builder, context, band)));
        } catch (Throwable t) {
            SITE_ERRORS.error(LOGGER, "[DungeonTrain] Nether-fortress siting failed; leaving it to vanilla", t);
            return Optional.empty();
        }
    }

    /**
     * Vanilla's own piece generation, with only the final height clamp retargeted onto the band. The random
     * draws are vanilla's and in vanilla's order, so a fortress has the same shape it would have in the
     * Nether.
     */
    private static void generatePieces(StructurePiecesBuilder builder, Structure.GenerationContext context,
                                       BandNetherStructures.Site band) {
        NetherFortressPieces.StartPiece start = new NetherFortressPieces.StartPiece(
                context.random(), context.chunkPos().getBlockX(2), context.chunkPos().getBlockZ(2));
        builder.addPiece(start);
        start.addChildren(start, builder, context.random());
        List<StructurePiece> pending = start.pendingChildren;

        while (!pending.isEmpty()) {
            int i = context.random().nextInt(pending.size());
            StructurePiece piece = pending.remove(i);
            piece.addChildren(start, builder, context.random());
        }

        builder.moveInsideHeights(context.random(),
                band.bandYInCore(PIECE_NETHER_Y_MIN), band.bandYInCore(PIECE_NETHER_Y_MAX));
    }
}
