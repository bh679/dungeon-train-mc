package games.brennan.dungeontrain.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which extra questions Submit for Review asks: redstone only for the machine parts, never for the
 * dust, levers and buttons that are usually decoration; loot for real loot and valuable blocks, never
 * for an empty chest.
 */
final class SubmitHintsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** The two tag files, read as the lists the production predicates test against. */
    private static Predicate<BlockState> tag(String name) {
        Set<String> ids = new java.util.HashSet<>();
        String path = "/data/dungeontrain/tags/block/" + name + ".json";
        try (InputStream in = SubmitHintsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            JsonArray values = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("values");
            values.forEach(v -> ids.add(v.getAsString()));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return state -> ids.contains(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).toString());
    }

    private static final Predicate<BlockState> REDSTONE = tag("advanced_redstone");
    private static final Predicate<BlockState> VALUABLE = tag("valuable_blocks");

    private static SubmitHints.Hints hints(Object... blocksAndNbt) {
        List<StructureTemplate.StructureBlockInfo> infos = new ArrayList<>();
        int x = 0;
        for (int i = 0; i < blocksAndNbt.length; i++) {
            Block block = (Block) blocksAndNbt[i];
            CompoundTag nbt = i + 1 < blocksAndNbt.length && blocksAndNbt[i + 1] instanceof CompoundTag t ? t : null;
            if (nbt != null) i++;
            infos.add(new StructureTemplate.StructureBlockInfo(new BlockPos(x++, 0, 0), block.defaultBlockState(), nbt));
        }
        List<TemplateLoot.LootBlock> loot = TemplateLoot.scan(infos, null, List.of());
        return SubmitHints.of(infos, loot, REDSTONE, VALUABLE);
    }

    @Test
    @DisplayName("each machine part on its own is redstone")
    void machineParts() {
        for (Block b : List.of(Blocks.REPEATER, Blocks.COMPARATOR, Blocks.OBSERVER, Blocks.PISTON, Blocks.STICKY_PISTON,
                Blocks.CALIBRATED_SCULK_SENSOR, Blocks.DROPPER, Blocks.DISPENSER)) {
            assertTrue(hints(Blocks.STONE, b).hasRedstone(), b.toString());
        }
    }

    @Test
    @DisplayName("dust, levers and buttons are decoration, not redstone worth asking about")
    void decorationIsNotRedstone() {
        SubmitHints.Hints h = hints(Blocks.REDSTONE_WIRE, Blocks.LEVER, Blocks.STONE_BUTTON,
                Blocks.OAK_BUTTON, Blocks.REDSTONE_TORCH, Blocks.STONE);
        assertFalse(h.hasRedstone());
        assertFalse(h.hasLoot());
    }

    @Test
    @DisplayName("a chest with a loot table is loot")
    void lootTableChest() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("LootTable", "minecraft:chests/simple_dungeon");
        SubmitHints.Hints h = hints(Blocks.CHEST, nbt);
        assertTrue(h.hasLoot());
        assertFalse(h.hasRedstone());
    }

    @Test
    @DisplayName("an empty chest is not loot")
    void emptyChest() {
        assertFalse(hints(Blocks.CHEST, Blocks.BARREL).hasLoot());
    }

    @Test
    @DisplayName("valuable blocks are loot by themselves")
    void valuableBlocks() {
        for (Block b : List.of(Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK, Blocks.EMERALD_BLOCK,
                Blocks.GOLD_BLOCK, Blocks.ANCIENT_DEBRIS, Blocks.BEACON)) {
            assertTrue(hints(Blocks.STONE, b).hasLoot(), b.toString());
        }
        assertFalse(hints(Blocks.IRON_BLOCK, Blocks.COPPER_BLOCK).hasLoot());
    }

    @Test
    @DisplayName("both at once, and nothing for an empty build")
    void bothAndNone() {
        SubmitHints.Hints both = hints(Blocks.OBSERVER, Blocks.DIAMOND_BLOCK);
        assertTrue(both.hasRedstone() && both.hasLoot());
        assertEquals(SubmitHints.Hints.NONE, hints());
    }

    @Test
    @DisplayName("the found blocks come back counted, most valuable loot first")
    void countedAndRanked() {
        CompoundTag table = new CompoundTag();
        table.putString("LootTable", "minecraft:chests/simple_dungeon");
        SubmitHints.Hints h = hints(Blocks.GOLD_BLOCK, Blocks.NETHERITE_BLOCK, Blocks.DIAMOND_BLOCK,
                Blocks.DIAMOND_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.CHEST, table, Blocks.REPEATER, Blocks.REPEATER, Blocks.OBSERVER);
        List<Block> loot = h.loot().stream().map(SubmitHints.Found::block).toList();
        assertEquals(Blocks.DIAMOND_BLOCK, loot.get(0), "three diamond blocks outrank one netherite");
        assertEquals(Blocks.NETHERITE_BLOCK, loot.get(1));
        assertEquals(Blocks.GOLD_BLOCK, loot.get(2));
        assertTrue(loot.contains(Blocks.CHEST));
        SubmitHints.Found chest = h.loot().stream().filter(f -> f.block() == Blocks.CHEST).findFirst().orElseThrow();
        assertEquals(SubmitHints.Kind.TABLE, chest.kind());
        assertEquals("minecraft:chests/simple_dungeon", chest.detail());
        assertEquals(3, h.loot().get(0).count());
        assertEquals(Blocks.REPEATER, h.redstone().get(0).block(), "most numerous machine part first");
        assertEquals(2, h.redstone().get(0).count());
    }
}
