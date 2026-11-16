package games.brennan.dungeontrain.portal;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins which blocks a dimensional carriage corridor refuses: storage that the mirror would split or duplicate, nothing else. */
class PortalCorridorContainersTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void refusesChestsBarrelsHoppersAndEveryShulkerBox() {
        List<Block> refused = List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.HOPPER,
            Blocks.SHULKER_BOX, Blocks.RED_SHULKER_BOX, Blocks.BLACK_SHULKER_BOX);
        for (Block block : refused) {
            assertTrue(PortalCorridorContainers.isRefusedInCorridor(block), block.toString());
        }
    }

    @Test
    void allowsEnderChestsAndOrdinaryBlocks() {
        List<Block> allowed = List.of(Blocks.ENDER_CHEST, Blocks.DISPENSER, Blocks.DROPPER,
            Blocks.FURNACE, Blocks.CRAFTING_TABLE, Blocks.TORCH, Blocks.STONE);
        for (Block block : allowed) {
            assertFalse(PortalCorridorContainers.isRefusedInCorridor(block), block.toString());
        }
    }

    @Test
    void refusesOnlyTheHopperMinecart() {
        assertTrue(PortalCorridorContainers.isRefusedCartInCorridor(Items.HOPPER_MINECART));
        for (Item item : List.of(Items.MINECART, Items.CHEST_MINECART, Items.FURNACE_MINECART,
                Items.TNT_MINECART, Items.HOPPER)) {
            assertFalse(PortalCorridorContainers.isRefusedCartInCorridor(item), item.toString());
        }
    }
}
