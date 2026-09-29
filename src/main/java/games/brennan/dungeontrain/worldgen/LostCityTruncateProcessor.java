package games.brennan.dungeontrain.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Lops the top off a Lost City building at placement: a "topped" tower or a block that lost its upper
 * floors, rolled per placement rather than shipped as another template.
 *
 * <p>Per placement one cut height is rolled between {@code min_fraction} and {@code max_fraction} of the
 * template's height, keyed on the placement origin so every block of the piece agrees. Everything above
 * {@code cut + jagged} is dropped; in the {@code jagged} band above the cut a block survives with a
 * probability that falls from full to none, so the edge is broken rather than sliced; the cut layer's own
 * solid blocks become a random {@code rubble} block. The pad layer ({@code y = 0}) is never touched.</p>
 *
 * <p>Runs in {@link #finalizeProcessing} — the only hook that sees the whole piece, and so its height.
 * Rotation and mirroring keep Y, so a placed block's local height is its world Y minus the origin's.
 * Entities are not processors' to filter: the few item frames a template carries above the cut pop as
 * items.</p>
 *
 * <p>Registered as {@code dungeontrain:lost_city_truncate}.</p>
 */
public final class LostCityTruncateProcessor extends StructureProcessor {

    public static final MapCodec<LostCityTruncateProcessor> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.floatRange(0.0F, 1.0F).fieldOf("min_fraction").forGetter(p -> p.minFraction),
            Codec.floatRange(0.0F, 1.0F).fieldOf("max_fraction").forGetter(p -> p.maxFraction),
            Codec.intRange(0, 64).optionalFieldOf("jagged", 3).forGetter(p -> p.jagged),
            BuiltInRegistries.BLOCK.byNameCodec().listOf().fieldOf("rubble").forGetter(p -> p.rubble)
    ).apply(i, LostCityTruncateProcessor::new));

    public static final StructureProcessorType<LostCityTruncateProcessor> TYPE = () -> CODEC;

    private static final int SALT = 0x7C07;

    private final float minFraction;
    private final float maxFraction;
    private final int jagged;
    private final List<Block> rubble;

    public LostCityTruncateProcessor(float minFraction, float maxFraction, int jagged, List<Block> rubble) {
        if (rubble.isEmpty()) throw new IllegalArgumentException("lost_city_truncate needs at least one rubble block");
        this.minFraction = Math.min(minFraction, maxFraction);
        this.maxFraction = Math.max(minFraction, maxFraction);
        this.jagged = jagged;
        this.rubble = List.copyOf(rubble);
    }

    private static long seed(BlockPos origin) {
        return origin.asLong() * 0x9E3779B97F4A7C15L + SALT;
    }

    /** The local height of the cut for a piece {@code height} blocks tall placed at {@code origin}, at least 1. */
    public int cutHeight(BlockPos origin, int height) {
        double t = LostCityStructures.hash01(seed(origin), 0, 0);
        int cut = (int) Math.round((minFraction + (maxFraction - minFraction) * t) * (height - 1));
        return Math.max(1, Math.min(height - 1, cut));
    }

    /**
     * Whether the block at local height {@code localY} survives a cut at {@code cut}: everything up to the
     * cut does, nothing above {@code cut + jagged} does, and in between the odds fall off linearly.
     */
    public boolean survives(BlockPos origin, BlockPos pos, int localY, int cut) {
        if (localY <= cut) return true;
        int above = localY - cut;
        if (above > jagged) return false;
        double keep = 1.0D - (double) above / (jagged + 1);
        return LostCityStructures.hash01(seed(origin) ^ pos.asLong(), pos.getX(), pos.getZ() * 31 + pos.getY()) < keep;
    }

    /** A rubble block for the cut layer at {@code pos}. */
    public BlockState rubbleAt(BlockPos origin, BlockPos pos) {
        int i = (int) (LostCityStructures.hash01(seed(origin) + pos.asLong(), pos.getZ(), pos.getX()) * rubble.size());
        return rubble.get(Math.min(i, rubble.size() - 1)).defaultBlockState();
    }

    /**
     * Applies the cut to {@code processed} (world positions) for a piece placed at {@code origin}: the
     * kept blocks, with the cut layer's solid blocks turned to rubble. Pure — used by the processor and its
     * tests.
     */
    public List<StructureTemplate.StructureBlockInfo> truncate(BlockPos origin,
                                                                List<StructureTemplate.StructureBlockInfo> processed) {
        int top = 0;
        for (StructureTemplate.StructureBlockInfo info : processed) {
            top = Math.max(top, info.pos().getY() - origin.getY());
        }
        int height = top + 1;
        if (height < 3) return processed;
        int cut = cutHeight(origin, height);
        List<StructureTemplate.StructureBlockInfo> out = new ArrayList<>(processed.size());
        for (StructureTemplate.StructureBlockInfo info : processed) {
            int localY = info.pos().getY() - origin.getY();
            if (localY == 0 || localY < cut) {
                out.add(info);
            } else if (localY == cut) {
                out.add(info.state().isAir() ? info
                        : new StructureTemplate.StructureBlockInfo(info.pos(), rubbleAt(origin, info.pos()), null));
            } else if (survives(origin, info.pos(), localY, cut)) {
                out.add(info);
            }
        }
        return out;
    }

    @Override
    public List<StructureTemplate.StructureBlockInfo> finalizeProcessing(ServerLevelAccessor level, BlockPos offset,
                                                                         BlockPos pivot,
                                                                         List<StructureTemplate.StructureBlockInfo> originals,
                                                                         List<StructureTemplate.StructureBlockInfo> processed,
                                                                         StructurePlaceSettings settings) {
        return truncate(offset, processed);
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
