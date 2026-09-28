package games.brennan.dungeontrain.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-driven block swaps that keep a block's state: the Lost City's facade recolours, decay and
 * overgrowth styles.
 *
 * <p>Vanilla's {@code minecraft:rule} processor outputs one fixed state, so it cannot recolour stairs,
 * slabs, panes or walls without flattening their facing, half and connections. Each {@link Swap} here
 * names a {@code from} block, a {@code to} block and a {@code chance}; a matching block becomes the
 * {@code to} block's default state with every same-named property copied over from the original
 * ({@link #carry}). A {@code to} of {@code minecraft:air} removes the block — decay restricted to the
 * blocks listed, so floors and the pad are never rotted.</p>
 *
 * <p>The roll is a hash of the placement origin and the block's world position, so a chunk regenerates
 * identically. The template's pad layer ({@code y = 0}) is never touched: {@link LostCityGroundProcessor}
 * reads it as natural ground and builds footing under it.</p>
 *
 * <p>Registered as {@code dungeontrain:lost_city_swap} and used from
 * {@code data/dungeontrain/worldgen/processor_list/lost_city/}.</p>
 */
public final class LostCitySwapProcessor extends StructureProcessor {

    /** One replacement rule. */
    public record Swap(Block from, Block to, float chance) {
        public static final Codec<Swap> CODEC = RecordCodecBuilder.create(i -> i.group(
                BuiltInRegistries.BLOCK.byNameCodec().fieldOf("from").forGetter(Swap::from),
                BuiltInRegistries.BLOCK.byNameCodec().fieldOf("to").forGetter(Swap::to),
                Codec.floatRange(0.0F, 1.0F).optionalFieldOf("chance", 1.0F).forGetter(Swap::chance)
        ).apply(i, Swap::new));
    }

    public static final MapCodec<LostCitySwapProcessor> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Swap.CODEC.listOf().fieldOf("swaps").forGetter(p -> p.swaps)
    ).apply(i, LostCitySwapProcessor::new));

    public static final StructureProcessorType<LostCitySwapProcessor> TYPE = () -> CODEC;

    private static final int SALT = 0x5A17;

    private final List<Swap> swaps;
    private final Map<Block, List<Swap>> byBlock;

    public LostCitySwapProcessor(List<Swap> swaps) {
        this.swaps = List.copyOf(swaps);
        Map<Block, List<Swap>> index = new HashMap<>();
        for (Swap swap : this.swaps) {
            index.computeIfAbsent(swap.from(), b -> new ArrayList<>()).add(swap);
        }
        index.replaceAll((b, list) -> List.copyOf(list));
        this.byBlock = Map.copyOf(index);
    }

    public List<Swap> swaps() {
        return swaps;
    }

    /**
     * The {@code to} block's default state with every property the two blocks share copied from
     * {@code from}: a north-facing top stair stays a north-facing top stair, a waterlogged pane stays
     * waterlogged.
     */
    public static BlockState carry(BlockState from, Block to) {
        BlockState out = to.defaultBlockState();
        for (Property<?> property : from.getProperties()) {
            if (out.hasProperty(property)) out = copy(out, from, property);
        }
        return out;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState out, BlockState from, Property<T> property) {
        return out.setValue(property, from.getValue(property));
    }

    /**
     * The swap that applies to {@code state} at world position {@code pos} for a piece placed at
     * {@code origin}, or {@code null} when none matches or every match failed its roll. Rules are tried in
     * data order; the first that rolls wins.
     */
    @Nullable
    public Swap pick(BlockState state, BlockPos origin, BlockPos pos) {
        List<Swap> candidates = byBlock.get(state.getBlock());
        if (candidates == null) return null;
        long seed = origin.asLong() * 0x9E3779B97F4A7C15L + SALT;
        double roll = LostCityStructures.hash01(seed ^ pos.asLong(), pos.getX() * 31 + pos.getZ(), pos.getY());
        double acc = 0.0D;
        for (Swap swap : candidates) {
            acc += swap.chance();
            if (roll < acc) return swap;
        }
        return null;
    }

    @Override
    @Nullable
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader world, BlockPos offset, BlockPos pivot,
                                                              StructureTemplate.StructureBlockInfo original,
                                                              StructureTemplate.StructureBlockInfo target,
                                                              StructurePlaceSettings settings) {
        if (target == null || original.pos().getY() == 0) return target;
        Swap swap = pick(target.state(), offset, target.pos());
        if (swap == null) return target;
        if (swap.to() == Blocks.AIR) return null;
        BlockState state = carry(target.state(), swap.to());
        return new StructureTemplate.StructureBlockInfo(target.pos(), state, state.hasBlockEntity() ? target.nbt() : null);
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
