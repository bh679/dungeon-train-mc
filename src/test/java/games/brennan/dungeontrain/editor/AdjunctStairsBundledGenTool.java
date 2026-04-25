package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.PillarAdjunct;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generator + integrity check for the bundled {@code adjunct_stairs.nbt}
 * resource.
 *
 * <p>Normally a {@code StructureTemplate} NBT comes from
 * {@code fillFromWorld} inside a running ServerLevel (via
 * {@code /dungeontrain editor pillar save}). That path needs a player
 * command source and can't be driven from a headless test. Instead, this
 * class assembles the CompoundTag by hand to match the layout
 * {@link PillarEditor} paints as its procedural stair fallback, then
 * writes it to the bundled resource path.</p>
 *
 * <p>{@link #generate} is gated behind the {@code generate-adjunct-stairs}
 * system property so the regular test run doesn't clobber the committed
 * file. Invoke explicitly:</p>
 *
 * <pre>./gradlew test --tests 'games.brennan.dungeontrain.editor.AdjunctStairsBundledGenTool.generate' -Dgenerate-adjunct-stairs=true</pre>
 *
 * <p>{@link #verify} always runs: it reads the committed NBT and checks
 * shape + a handful of spot positions. If someone corrupts the file or the
 * adjunct footprint changes without regeneration, the build fails loudly.</p>
 */
final class AdjunctStairsBundledGenTool {

    /** Path relative to the Gradle project root (= working directory for {@code :test}). */
    private static final Path RESOURCE_PATH = Paths.get(
        "src/main/resources/data/dungeontrain/pillars/adjunct_stairs/default.nbt");

    /** DataVersion for Minecraft 1.20.1 — matches {@code SharedConstants.getCurrentVersion().getDataVersion().getVersion()}. */
    private static final int DATA_VERSION = 3465;

    private static final int X_SIZE = PillarAdjunct.STAIRS.xSize();
    private static final int Y_SIZE = PillarAdjunct.STAIRS.ySize();
    private static final int Z_SIZE = PillarAdjunct.STAIRS.zSize();

    private static final int PALETTE_STONE_BRICKS = 0;
    private static final int PALETTE_STAIRS = 1;
    private static final int PALETTE_AIR = 2;

    @Test
    @DisplayName("generate: write bundled adjunct_stairs.nbt (opt-in)")
    @EnabledIfSystemProperty(named = "generate-adjunct-stairs", matches = "true")
    void generate() throws IOException {
        CompoundTag root = buildStairsTemplate();
        Files.createDirectories(RESOURCE_PATH.getParent());
        NbtIo.writeCompressed(root, RESOURCE_PATH.toFile());
    }

    @Test
    @DisplayName("bundled adjunct_stairs.nbt matches the 3x8x3 procedural staircase pattern")
    void verify() throws IOException {
        assertTrue(Files.isRegularFile(RESOURCE_PATH),
            "Bundled NBT missing at " + RESOURCE_PATH.toAbsolutePath()
                + " — regenerate with -Dgenerate-adjunct-stairs=true");

        CompoundTag root = NbtIo.readCompressed(RESOURCE_PATH.toFile());

        ListTag size = root.getList("size", Tag.TAG_INT);
        assertEquals(X_SIZE, ((IntTag) size.get(0)).getAsInt(), "size.x");
        assertEquals(Y_SIZE, ((IntTag) size.get(1)).getAsInt(), "size.y");
        assertEquals(Z_SIZE, ((IntTag) size.get(2)).getAsInt(), "size.z");

        ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
        assertEquals(3, palette.size(), "palette entries");
        assertEquals("minecraft:stone_bricks",
            palette.getCompound(PALETTE_STONE_BRICKS).getString("Name"));
        assertEquals("minecraft:stone_brick_stairs",
            palette.getCompound(PALETTE_STAIRS).getString("Name"));
        assertEquals("minecraft:air",
            palette.getCompound(PALETTE_AIR).getString("Name"));

        CompoundTag stairProps = palette.getCompound(PALETTE_STAIRS).getCompound("Properties");
        assertEquals("south", stairProps.getString("facing"),
            "template is authored in its -Z-side orientation; Mirror.LEFT_RIGHT flips to NORTH on +Z side");

        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        assertEquals(X_SIZE * Y_SIZE * Z_SIZE, blocks.size(), "blocks list covers full 3x8x3 volume");

        // stepZ = min(y, zs-1): Y=0 → stepZ=0, Y=1 → stepZ=1, Y=2..7 → stepZ=2.
        // dz < stepZ → stone_bricks, dz == stepZ → stair, dz > stepZ → air.
        assertStateAt(blocks, 0, 0, 0, PALETTE_STAIRS);       // first step
        assertStateAt(blocks, 2, 0, 0, PALETTE_STAIRS);       // first step, all 3 X cols
        assertStateAt(blocks, 0, 0, 1, PALETTE_AIR);          // above future step
        assertStateAt(blocks, 0, 0, 2, PALETTE_AIR);          // above future step
        assertStateAt(blocks, 0, 1, 0, PALETTE_STONE_BRICKS); // fill under Y=1 step
        assertStateAt(blocks, 0, 1, 1, PALETTE_STAIRS);       // Y=1 step
        assertStateAt(blocks, 0, 2, 2, PALETTE_STAIRS);       // Y=2 step at max Z
        assertStateAt(blocks, 0, 7, 0, PALETTE_STONE_BRICKS); // wall cap, fill
        assertStateAt(blocks, 0, 7, 2, PALETTE_STAIRS);       // wall cap, stair topper

        assertEquals(DATA_VERSION, root.getInt("DataVersion"));
    }

    private static CompoundTag buildStairsTemplate() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", DATA_VERSION);

        ListTag size = new ListTag();
        size.add(IntTag.valueOf(X_SIZE));
        size.add(IntTag.valueOf(Y_SIZE));
        size.add(IntTag.valueOf(Z_SIZE));
        root.put("size", size);

        ListTag palette = new ListTag();
        palette.add(namedState("minecraft:stone_bricks"));
        CompoundTag stairsState = namedState("minecraft:stone_brick_stairs");
        CompoundTag stairsProps = new CompoundTag();
        stairsProps.putString("facing", "south");
        stairsProps.putString("half", "bottom");
        stairsProps.putString("shape", "straight");
        stairsProps.putString("waterlogged", "false");
        stairsState.put("Properties", stairsProps);
        palette.add(stairsState);
        palette.add(namedState("minecraft:air"));
        root.put("palette", palette);

        // Iterate (y, x, z) so the output is byte-deterministic across regens.
        ListTag blocks = new ListTag();
        for (int y = 0; y < Y_SIZE; y++) {
            int stepZ = Math.min(y, Z_SIZE - 1);
            for (int x = 0; x < X_SIZE; x++) {
                for (int z = 0; z < Z_SIZE; z++) {
                    int state;
                    if (z < stepZ) state = PALETTE_STONE_BRICKS;
                    else if (z == stepZ) state = PALETTE_STAIRS;
                    else state = PALETTE_AIR;
                    blocks.add(blockEntry(x, y, z, state));
                }
            }
        }
        root.put("blocks", blocks);

        root.put("entities", new ListTag());

        return root;
    }

    private static CompoundTag namedState(String blockId) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        return state;
    }

    private static CompoundTag blockEntry(int x, int y, int z, int paletteIndex) {
        CompoundTag block = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(x));
        pos.add(IntTag.valueOf(y));
        pos.add(IntTag.valueOf(z));
        block.put("pos", pos);
        block.putInt("state", paletteIndex);
        return block;
    }

    private static void assertStateAt(ListTag blocks, int x, int y, int z, int expected) {
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            if (((IntTag) pos.get(0)).getAsInt() == x
                && ((IntTag) pos.get(1)).getAsInt() == y
                && ((IntTag) pos.get(2)).getAsInt() == z) {
                assertEquals(expected, block.getInt("state"),
                    "state at (" + x + ", " + y + ", " + z + ")");
                return;
            }
        }
        fail("no block entry for (" + x + ", " + y + ", " + z + ")");
    }
}
