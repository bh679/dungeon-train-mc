package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.editor.ContainerContentsEntry;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.editor.ContainerContentsRoller;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a difficulty swap clears, judged on the decisions and on remembered portal-room snapshots. */
final class DifficultyLootClearTest {

    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
    }

    @Test
    @DisplayName("only a recorded, different difficulty is stale; unknown is never grounds to delete")
    void staleness() {
        assertTrue(DifficultyLootClear.isStale("peaceful", "hard"));
        assertFalse(DifficultyLootClear.isStale("hard", "hard"));
        assertFalse(DifficultyLootClear.isStale("", "hard"));
        assertFalse(DifficultyLootClear.isStale(null, "hard"));
    }

    private static CompoundTag rolledChestTag() {
        ContainerContentsPool pool = new ContainerContentsPool(List.of(
            new ContainerContentsEntry(ResourceLocation.withDefaultNamespace("bread"), 4, 1)), 3, 3);
        return ContainerContentsRoller.roll(pool, Blocks.CHEST.defaultBlockState(), new BlockPos(1, 1, 1), 42L, 5,
            null, registries, null);
    }

    private static CompoundTag cell(String block, CompoundTag be) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", block);
        CompoundTag cell = new CompoundTag();
        cell.put("s", state);
        cell.put("b", be);
        return cell;
    }

    private static CompoundTag entity(String id, String itemKey) {
        CompoundTag ent = new CompoundTag();
        ent.putString("id", id);
        if (itemKey != null) ent.put(itemKey, new CompoundTag());
        return ent;
    }

    @Test
    @DisplayName("a snapshot keeps untouched rolled loot and loses everything a player changed or left")
    void scrubSnapshot() {
        CompoundTag untouched = rolledChestTag();
        CompoundTag looted = rolledChestTag();
        looted.getList("Items", 10).remove(0);
        CompoundTag playerChest = new CompoundTag();
        ListTag stash = new ListTag();
        CompoundTag diamond = new CompoundTag();
        diamond.putString("id", "minecraft:diamond");
        diamond.putInt("count", 5);
        diamond.putByte("Slot", (byte) 0);
        stash.add(diamond);
        playerChest.put("Items", stash);
        CompoundTag tv = playerChest.copy();

        ListTag cells = new ListTag();
        cells.add(cell("minecraft:chest", untouched));
        cells.add(cell("minecraft:chest", looted));
        cells.add(cell("minecraft:barrel", playerChest));
        cells.add(cell("vista:tv", tv));
        ListTag ents = new ListTag();
        ents.add(entity("minecraft:item", "Item"));
        ents.add(entity("minecraft:item_frame", "Item"));
        ents.add(entity("minecraft:chest_minecart", "Items"));
        ents.add(entity("minecraft:zombie", null));
        CompoundTag snapshot = new CompoundTag();
        snapshot.put("cells", cells);
        snapshot.put("ents", ents);

        DifficultyLootClear.scrubSnapshot(snapshot, registries,
            state -> state.getString("Name").equals("vista:tv"));

        ListTag outCells = snapshot.getList("cells", 10);
        assertTrue(outCells.getCompound(0).getCompound("b").contains("Items"), "untouched roll stays");
        assertFalse(outCells.getCompound(1).getCompound("b").contains("Items"), "looted roll is cleared");
        assertFalse(outCells.getCompound(2).getCompound("b").contains("Items"), "player stash is cleared");
        assertTrue(outCells.getCompound(3).getCompound("b").contains("Items"), "a TV's tuner slot is left alone");

        ListTag outEnts = snapshot.getList("ents", 10);
        assertEquals(3, outEnts.size(), "the item entity is gone, the others stay");
        assertEquals(StringTag.valueOf("minecraft:item_frame"), outEnts.getCompound(0).get("id"));
        assertFalse(outEnts.getCompound(0).contains("Item"), "the frame is emptied");
        assertFalse(outEnts.getCompound(1).contains("Items"), "the minecart is emptied");
    }

    @Test
    @DisplayName("a pending loot table is left: nobody has opened it")
    void pendingLootTableKept() {
        CompoundTag be = new CompoundTag();
        be.putString("LootTable", "minecraft:chests/simple_dungeon");
        be.put("Items", new ListTag());
        ListTag cells = new ListTag();
        cells.add(cell("minecraft:chest", be));
        CompoundTag snapshot = new CompoundTag();
        snapshot.put("cells", cells);
        DifficultyLootClear.scrubSnapshot(snapshot, registries, state -> false);
        assertTrue(snapshot.getList("cells", 10).getCompound(0).getCompound("b").contains("Items"));
    }
}
