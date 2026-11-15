package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import games.brennan.dungeontrain.item.VariantClipboardItem;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plain chest row's trapped-chest chance ({@link VariantState#trapped()}): 0 never traps, 100 always
 * does, the roll is seeded, both halves of a double chest agree, only a plain chest ever swaps, and the
 * chance rides the row (sidecar JSON, clipboard), never the loot.
 */
final class TrappedChestRollTest {

    private static final long SEED = 12345L;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static int trappedCount(int chance, BlockState state) {
        int hits = 0;
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                if (TrappedChestRoll.rolls(chance, state, new BlockPos(x, 1, z), SEED, 7)) hits++;
            }
        }
        return hits;
    }

    @Test
    @DisplayName("0% never traps, 100% always does, 50% traps about half")
    void chanceBounds() {
        BlockState chest = Blocks.CHEST.defaultBlockState();
        assertEquals(0, trappedCount(0, chest));
        assertEquals(400, trappedCount(100, chest));
        int half = trappedCount(50, chest);
        assertTrue(half > 140 && half < 260, "50% rolled " + half + " of 400");
    }

    @Test
    @DisplayName("the roll is the same every time for the same seed, carriage and cell")
    void deterministic() {
        BlockState chest = Blocks.CHEST.defaultBlockState();
        BlockPos cell = new BlockPos(4, 1, 2);
        boolean first = TrappedChestRoll.rolls(40, chest, cell, SEED, 3);
        for (int i = 0; i < 10; i++) {
            assertEquals(first, TrappedChestRoll.rolls(40, chest, cell, SEED, 3));
        }
    }

    @Test
    @DisplayName("both halves of a double chest land on the same answer")
    void doubleChestHalvesAgree() {
        BlockState left = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState right = left.setValue(ChestBlock.TYPE, ChestType.RIGHT);
        for (int x = 0; x < 30; x++) {
            BlockPos leftPos = new BlockPos(x, 1, 5);
            BlockPos rightPos = leftPos.relative(ChestBlock.getConnectedDirection(left));
            assertEquals(leftPos, rightPos.relative(ChestBlock.getConnectedDirection(right)));
            assertEquals(TrappedChestRoll.rolls(50, left, leftPos, SEED, 2),
                TrappedChestRoll.rolls(50, right, rightPos, SEED, 2), "halves at x=" + x);
        }
    }

    @Test
    @DisplayName("only a plain chest swaps; the trapped chest keeps facing and chest type")
    void onlyPlainChest() {
        assertEquals(0, trappedCount(100, Blocks.BARREL.defaultBlockState()));
        assertEquals(0, trappedCount(100, Blocks.TRAPPED_CHEST.defaultBlockState()));
        BlockState half = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.EAST).setValue(ChestBlock.TYPE, ChestType.RIGHT);
        BlockState trapped = TrappedChestRoll.trapped(half);
        assertTrue(trapped.is(Blocks.TRAPPED_CHEST));
        assertEquals(Direction.EAST, trapped.getValue(ChestBlock.FACING));
        assertEquals(ChestType.RIGHT, trapped.getValue(ChestBlock.TYPE));

        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:chest");
        assertEquals("minecraft:trapped_chest", TrappedChestRoll.nbtFor(trapped, nbt).getString("id"));
        assertSame(nbt, TrappedChestRoll.nbtFor(half, nbt), "a plain chest keeps its NBT");
    }

    @Test
    @DisplayName("the Trap pill steps wrap at both ends of 0-100")
    void wrapChance() {
        assertEquals(5, TrappedChestRoll.wrapChance(0, 5));
        assertEquals(100, TrappedChestRoll.wrapChance(95, 5));
        assertEquals(0, TrappedChestRoll.wrapChance(100, 5));
        assertEquals(100, TrappedChestRoll.wrapChance(0, -5));
        assertEquals(0, TrappedChestRoll.wrapChance(3, -5));
        assertEquals(100, TrappedChestRoll.wrapChance(98, 5));
    }

    @Test
    @DisplayName("the whole-entry apply places a 100% chest row trapped, a 0% row plain, and keeps the loot link")
    void wholeEntryApply() {
        VariantState row = VariantState.of(Blocks.CHEST.defaultBlockState()).withLinkedLootPrefabId("loot");
        BlockPos cell = new BlockPos(5, 1, 1);
        assertSame(Blocks.CHEST, RotationApplier.apply(row.state(), row, cell, SEED, 0, 0).getBlock());
        VariantState trappedRow = row.withTrapped(100);
        assertSame(Blocks.TRAPPED_CHEST, RotationApplier.apply(row.state(), trappedRow, cell, SEED, 0, 0).getBlock());
        assertEquals("loot", trappedRow.linkedLootPrefabId());
        assertEquals(100, row.withTrapped(250).trapped());
        assertEquals(0, row.withTrapped(-5).trapped());
        assertEquals(100, trappedRow.withWeight(3).withRotation(VariantRotation.NONE).trapped(), "copies keep it");
    }

    @Test
    @DisplayName("the chance survives the sidecar JSON and the clipboard; 0 is never written")
    void roundTrips() {
        List<VariantState> cell = List.of(
            VariantState.of(Blocks.CHEST.defaultBlockState()).withLinkedLootPrefabId("loot").withTrapped(30),
            VariantState.of(Blocks.CHEST.defaultBlockState()));
        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendCellJson(sb, cell, 0);
        String json = sb.toString();
        assertEquals(1, json.split("\"trapped\"", -1).length - 1, "only a non-zero chance is written: " + json);
        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        CarriageVariantBlocks.ParsedCell parsed = CarriageVariantBlocks.parseCellValue(
            JsonParser.parseString(json), blocks, "test", BlockPos.ZERO);
        assertNotNull(parsed);
        assertEquals(30, parsed.states().get(0).trapped());
        assertEquals("loot", parsed.states().get(0).linkedLootPrefabId());
        assertEquals(0, parsed.states().get(1).trapped());
        assertTrue(parsed.states().get(1).isPlainBareString());

        List<VariantState> back = VariantClipboardItem.decodeStates(VariantClipboardItem.encodeStates(cell, 0));
        assertEquals(30, back.get(0).trapped());
        assertEquals(0, back.get(1).trapped());
    }

    @Test
    @DisplayName("every chest row in the TNT trap rooms is 100% trapped; nothing else in those rooms is")
    void tntTrapRoomsAreTrapped() throws IOException {
        Path rooms = games.brennan.dungeontrain.RepoPaths.root()
            .resolve("src/main/resources/data/dungeontrain/portals/room");
        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        int chests = 0;
        for (String room : List.of("trap", "tnt", "tntfull", "tntlong")) {
            var variants = JsonParser.parseString(Files.readString(rooms.resolve(room + ".variants.json")))
                .getAsJsonObject().getAsJsonObject("variants");
            for (var cell : variants.entrySet()) {
                CarriageVariantBlocks.ParsedCell parsed = CarriageVariantBlocks.parseCellValue(
                    cell.getValue(), blocks, room, BlockPos.ZERO);
                if (parsed == null) continue;
                for (VariantState s : parsed.states()) {
                    String where = room + " " + cell.getKey() + " " + s.state();
                    if (TrappedChestRoll.isTrappable(s.state())) {
                        assertEquals(100, s.trapped(), where);
                        chests++;
                    } else {
                        assertEquals(0, s.trapped(), where);
                    }
                }
            }
        }
        assertEquals(21, chests, "chest rows across the four TNT trap rooms");
        assertFalse(Files.readString(games.brennan.dungeontrain.RepoPaths.root()
            .resolve("src/main/resources/data/dungeontrain/prefabs/loot/loot.json")).contains("trap"),
            "the shared loot prefab carries no trap");
    }
}
