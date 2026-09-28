package games.brennan.dungeontrain.train;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * The vertical half of the flip — the one axis Minecraft has no mirror for, so
 * {@link ContentsFlip#verticallyFlipped} rebuilds the template from its own NBT.
 *
 * <p>Needs a headless Minecraft bootstrap so block states resolve.</p>
 */
final class ContentsFlipVerticalTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A 1×3×1 column: a bottom slab on the floor, stone at the top, air between. */
    private static StructureTemplate column() {
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        CompoundTag tag = new CompoundTag();
        tag.put("size", ints(1, 3, 1));
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(slab));
        palette.add(NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        tag.put("palette", palette);
        ListTag blocks = new ListTag();
        blocks.add(block(0, 0, 0, 0));
        blocks.add(block(0, 2, 0, 1));
        tag.put("blocks", blocks);
        tag.put("entities", new ListTag());
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());

        StructureTemplate template = new StructureTemplate();
        template.load(BuiltInRegistries.BLOCK.asLookup(), tag);
        return template;
    }

    private static ListTag ints(int... values) {
        ListTag list = new ListTag();
        for (int v : values) list.add(IntTag.valueOf(v));
        return list;
    }

    private static CompoundTag block(int x, int y, int z, int state) {
        CompoundTag t = new CompoundTag();
        t.put("pos", ints(x, y, z));
        t.putInt("state", state);
        return t;
    }

    /** The flipped template's cells, read back off its saved NBT as {@code y → state}. */
    private static Map<Integer, BlockState> cellsByY(StructureTemplate template) {
        CompoundTag saved = template.save(new CompoundTag());
        ListTag palette = saved.getList("palette", Tag.TAG_COMPOUND);
        Map<Integer, BlockState> out = new HashMap<>();
        for (Tag t : saved.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag b = (CompoundTag) t;
            int y = b.getList("pos", Tag.TAG_INT).getInt(1);
            out.put(y, NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),
                palette.getCompound(b.getInt("state"))));
        }
        return out;
    }

    @Test
    @DisplayName("a vertical flip moves each cell to size-1-y and flips the state with it")
    void flipsPositionsAndStates() {
        StructureTemplate flipped = ContentsFlip.verticallyFlipped(column(),
            BuiltInRegistries.BLOCK.asLookup());
        Map<Integer, BlockState> cells = cellsByY(flipped);

        assertEquals(new net.minecraft.core.Vec3i(1, 3, 1), flipped.getSize(), "the box is unchanged");
        assertEquals(Blocks.STONE, cells.get(0).getBlock(), "the roof block fell to the floor");
        BlockState slab = cells.get(2);
        assertEquals(Blocks.STONE_SLAB, slab.getBlock(), "the floor slab rose to the ceiling");
        assertEquals(SlabType.TOP, slab.getValue(BlockStateProperties.SLAB_TYPE),
            "and hangs from it rather than floating mid-cell");
    }

    @Test
    @DisplayName("the flipped copy is a separate template, cached per source")
    void cachesPerSourceWithoutMutatingIt() {
        StructureTemplate source = column();
        StructureTemplate flipped = ContentsFlip.verticallyFlipped(source,
            BuiltInRegistries.BLOCK.asLookup());
        assertNotSame(source, flipped, "the source template must not be mutated in place");
        assertEquals(Blocks.STONE_SLAB, cellsByY(source).get(0).getBlock(), "source still authored");
        // A second call is the same object — the stamp path asks for this on every carriage spawn.
        assertEquals(flipped, ContentsFlip.verticallyFlipped(source, BuiltInRegistries.BLOCK.asLookup()));
    }
}
