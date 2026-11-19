package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvBlocks;
import games.brennan.dungeontrain.compat.vista.TvItems;
import net.mehvahdjukaar.vista.common.connection.IConnectedBlock;
import net.mehvahdjukaar.vista.common.tv.TVBlock;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/**
 * A broken TV remembers the stream it was on: the TV item it drops carries {@code TV_STREAM} 1 or 2
 * when the screen was switched on and tuned to the Live Feed, nothing when it was off. Put on a
 * head or placed again, it comes back on that stream ({@code client/live/HeadViewerPanes},
 * {@code mixin/vista/TVBlockMixin}). Vista drops the TV through its loot table, so the drop list of
 * {@link BlockDropsEvent} is where the item is tagged.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TvDropMemory {

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private TvDropMemory() {}

    /** The stream a broken TV remembers: 1 or 2 while switched on and tuned, else 0. Pure, for the tests. */
    static int streamOf(boolean on, boolean liveTuned, boolean secondCassette) {
        if (!on || !liveTuned) return 0;
        return secondCassette ? 2 : 1;
    }

    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        BlockState state = event.getState();
        if (!TvBlocks.isTelevision(state) || !state.hasProperty(TVBlock.POWER_STATE)) return;
        // A joined screen keeps its tuner on the master cell: ask for it, and fall back to this cell's own entity.
        BlockEntity be = state.getBlock() instanceof IConnectedBlock joined
            ? joined.findMasterBlockEntity(event.getLevel(), event.getPos(), state) : null;
        if (be == null) be = event.getBlockEntity();
        ItemStack cassette = be instanceof TVBlockEntity tv ? tv.getItem(0) : ItemStack.EMPTY;
        int stream = streamOf(state.getValue(TVBlock.POWER_STATE).isOn(), TvBlocks.isLiveCassette(cassette), TvBlocks.isSecondCassette(cassette));
        for (ItemEntity drop : event.getDrops()) {
            if (TvItems.isTv(drop.getItem())) drop.setItem(TvItems.withStream(drop.getItem(), stream));
        }
        LOGGER.debug("[DungeonTrain] TV broken at {}: remembers stream {} ({} drop(s))", event.getPos(), stream, event.getDrops().size());
    }
}
