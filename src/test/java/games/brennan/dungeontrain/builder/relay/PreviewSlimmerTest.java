package games.brennan.dungeontrain.builder.relay;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A big build's picture made light enough to send. */
class PreviewSlimmerTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Map<BlockPos, Integer> solidBox(int w, int h, int d, int state) {
        Map<BlockPos, Integer> cells = new HashMap<>();
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) for (int z = 0; z < d; z++) {
            cells.put(new BlockPos(x, y, z), state);
        }
        return cells;
    }

    @Test
    void aSolidCubeKeepsOnlyItsSkin() {
        Map<BlockPos, Integer> picture = PreviewSlimmer.picture(solidBox(3, 3, 3, 1), 1);
        assertEquals(26, picture.size());
        assertFalse(picture.containsKey(new BlockPos(1, 1, 1)));
    }

    @Test
    void downsampleHalvesTheBoundsAndPicksTheMostCommonState() {
        Map<BlockPos, Integer> cells = solidBox(4, 4, 4, 2);
        // One group of eight is mostly state 5.
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) {
            cells.put(new BlockPos(x, y, 0), 5);
        }
        cells.put(new BlockPos(0, 0, 1), 5);
        Map<BlockPos, Integer> small = PreviewSlimmer.downsample(cells, 2);
        assertEquals(8, small.size());
        assertEquals(5, small.get(BlockPos.ZERO));
        assertEquals(2, small.get(new BlockPos(1, 1, 1)));
    }

    @Test
    void downsampleTiesGoToTheLowerIndex() {
        Map<BlockPos, Integer> cells = new HashMap<>();
        cells.put(new BlockPos(0, 0, 0), 7);
        cells.put(new BlockPos(1, 0, 0), 3);
        assertEquals(3, PreviewSlimmer.downsample(cells, 2).get(BlockPos.ZERO));
    }

    /** A structure-template tag of a hollow {@code w×h×d} stone room, its air saved with it. */
    private static CompoundTag hollowRoom(int w, int h, int d) {
        CompoundTag tag = new CompoundTag();
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.CHEST.defaultBlockState()));
        tag.put("palette", palette);
        ListTag blocks = new ListTag();
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) for (int z = 0; z < d; z++) {
            boolean wall = x == 0 || y == 0 || z == 0 || x == w - 1 || y == h - 1 || z == d - 1;
            CompoundTag block = new CompoundTag();
            block.put("pos", ints(x, y, z));
            block.putInt("state", wall ? 1 : (x == 2 && y == 1 && z == 2 ? 2 : 0));
            if (!wall && x == 2 && y == 1 && z == 2) {
                CompoundTag nbt = new CompoundTag();
                nbt.putString("id", "minecraft:chest");
                block.put("nbt", nbt);
            }
            blocks.add(block);
        }
        tag.put("blocks", blocks);
        tag.put("entities", new ListTag());
        tag.put("size", ints(w, h, d));
        return tag;
    }

    private static ListTag ints(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x));
        list.add(IntTag.valueOf(y));
        list.add(IntTag.valueOf(z));
        return list;
    }

    @Test
    void aFullSizeRoomIsTooBigAsItIsButSendsOnceSlimmed() {
        CompoundTag room = hollowRoom(60, 40, 60);
        assertNull(BuilderRelayPreview.encode(room), "144,000 cells with their air: over the cap");

        PreviewSlimmer.Source source = PreviewSlimmer.read(room, BuiltInRegistries.BLOCK.asLookup());
        assertNotNull(source);
        int walls = 60 * 40 * 60 - 58 * 38 * 58;
        assertEquals(walls + 1, source.blocks(), "every non-air block counted, the chest inside included");

        byte[] sent = null;
        int factor = 1;
        for (; factor <= PreviewSlimmer.MAX_FACTOR && sent == null; factor++) {
            sent = BuilderRelayPreview.encode(source.slim(factor));
        }
        assertNotNull(sent, "some factor fits, so the tile gets a picture");
        assertTrue(factor - 1 >= 2, "a room this big has a skin over the block cap: drawn coarser");

        CompoundTag back = BuilderRelayPreview.decode(sent);
        CompoundTag real = back.getCompound(PreviewSlimmer.PREVIEW_KEY);
        assertEquals(walls + 1, real.getInt("blocks"));
        ListTag size = real.getList("size", Tag.TAG_INT);
        assertEquals(60, size.getInt(0));
        assertEquals(40, size.getInt(1));
        assertEquals(60, size.getInt(2));
        assertTrue(back.getList("entities", Tag.TAG_COMPOUND).isEmpty());

        StructureTemplate template = new StructureTemplate();
        template.load(BuiltInRegistries.BLOCK.asLookup(), back);
        assertTrue(template.getSize().getX() <= 30, "the slimmed template's own size is its picture's");
    }

    @Test
    void aMidSizeRoomFitsAtFullDetailOnceItsAirAndInsideAreGone() {
        CompoundTag room = hollowRoom(30, 20, 30);
        assertNull(BuilderRelayPreview.encode(room));
        PreviewSlimmer.Source source = PreviewSlimmer.read(room, BuiltInRegistries.BLOCK.asLookup());
        CompoundTag slim = source.slim(1);
        assertNotNull(BuilderRelayPreview.encode(slim));
        // The chest inside is behind the walls: the picture has no use for it, or its NBT.
        for (Tag t : slim.getList("blocks", Tag.TAG_COMPOUND)) {
            assertFalse(((CompoundTag) t).contains("nbt"));
            assertFalse(((CompoundTag) t).getInt("state") == 2);
        }
        assertEquals(1, slim.getCompound(PreviewSlimmer.PREVIEW_KEY).getInt("factor"));
    }

    @Test
    void nothingToReadIsNull() {
        assertNull(PreviewSlimmer.read(new CompoundTag(), BuiltInRegistries.BLOCK.asLookup()));
        CompoundTag onlyAir = hollowRoom(3, 3, 3);
        ListTag blocks = onlyAir.getList("blocks", Tag.TAG_COMPOUND);
        for (Tag t : blocks) ((CompoundTag) t).putInt("state", 0);
        assertNull(PreviewSlimmer.read(onlyAir, BuiltInRegistries.BLOCK.asLookup()));
    }
}
