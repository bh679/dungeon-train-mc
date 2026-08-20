package games.brennan.dungeontrain.worldgen.structure;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.util.LogFirstN;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Nether fortresses in the Nether band's core — the real thing, sited the way the real Nether sites them.
 *
 * <p>The pieces are vanilla's: {@link NetherFortressPieces.StartPiece} grows the same bridges, corridors,
 * blaze spawners, wither-skeleton halls and nether-wart rooms as {@code minecraft:fortress}, and the
 * structure set that drives this carries vanilla's spacing, separation and salt. The datapack entry also
 * carries vanilla's {@code spawn_overrides}, which is what makes blazes and wither skeletons spawn inside
 * the fortress rather than merely decorate it.</p>
 *
 * <p>Only the <b>Y</b> differs, and only because it has to. Vanilla drops a fortress at a flat Nether-space
 * Y 64 and clamps its pieces into Nether-space 48–70; the band lives in the overworld, where those world Ys
 * describe ordinary terrain. {@link BandNetherStructures.Site#bandY} translates each of them onto track
 * level by the same {@code bedY} ↔ Nether-centre mapping the core terrain itself uses, so a fortress lands
 * in exactly the slice of Nether the band stamped there.</p>
 *
 * <p>Where a fortress straddles the train's corridor the track is carved straight through it — the
 * corridor clearance sweep runs last and wins, by design. Riding through a fortress is the point.</p>
 */
public class BandNetherFortressStructure extends Structure {

    public static final MapCodec<BandNetherFortressStructure> CODEC = simpleCodec(BandNetherFortressStructure::new);

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN SITE_ERRORS = new LogFirstN(5);

    /** Vanilla's Nether-space start height, and the window it clamps its pieces into. */
    private static final int START_NETHER_Y = 64;
    private static final int PIECE_NETHER_Y_MIN = 48;
    private static final int PIECE_NETHER_Y_MAX = 70;

    /** Blocks a fortress can sprawl past its start chunk — its bridges run a long way. */
    private static final int FOOTPRINT_RADIUS = 80;

    public BandNetherFortressStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        try {
            BandNetherStructures.Site site = BandNetherStructures.open(context, FOOTPRINT_RADIUS);
            if (site == null) return Optional.empty();

            ChunkPos chunkPos = context.chunkPos();
            if (!site.biomeAllows(this.biomes(), chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ())) {
                return Optional.empty();
            }

            BlockPos start = new BlockPos(chunkPos.getMinBlockX(), site.bandYInCore(START_NETHER_Y),
                    chunkPos.getMinBlockZ());
            return Optional.of(new GenerationStub(start, builder -> generatePieces(builder, context, site)));
        } catch (Throwable t) {
            SITE_ERRORS.error(LOGGER, "[DungeonTrain] Nether-fortress siting failed; skipping this fortress", t);
            return Optional.empty();
        }
    }

    /**
     * Vanilla's own piece generation, with only the final height clamp retargeted onto the band. The
     * random draws are vanilla's and in vanilla's order, so a fortress has the same shape it would have in
     * the Nether.
     */
    private static void generatePieces(StructurePiecesBuilder builder, GenerationContext context,
                                       BandNetherStructures.Site site) {
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
                site.bandYInCore(PIECE_NETHER_Y_MIN), site.bandYInCore(PIECE_NETHER_Y_MAX));
    }

    /**
     * Vanilla's biome filter, deliberately skipped — {@link #findGenerationPoint} already applied the
     * Nether biome rule against the real Nether's own biome source, which is exact regardless of what the
     * overworld biome source reports for the column (and it reports the original overworld biome below sea
     * level, where a fortress is perfectly entitled to sit).
     */
    @Override
    public Optional<GenerationStub> findValidGenerationPoint(GenerationContext context) {
        return this.findGenerationPoint(context);
    }

    @Override
    public StructureType<?> type() {
        return ModStructureTypes.NETHER_FORTRESS.get();
    }
}
