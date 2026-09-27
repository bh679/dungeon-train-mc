package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

import java.util.ArrayList;
import java.util.List;

/** Hides {@link DisabledModContent} gear and ores from every creative tab and the creative search. */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DisabledModCreativeTabs {

    private DisabledModCreativeTabs() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        List<ItemStack> disabled = new ArrayList<>();
        for (ItemStack stack : event.getParentEntries()) {
            if (DisabledModContent.isDisabledItem(stack)) {
                disabled.add(stack);
            }
        }
        for (ItemStack stack : event.getSearchEntries()) {
            if (DisabledModContent.isDisabledItem(stack) && !disabled.contains(stack)) {
                disabled.add(stack);
            }
        }
        for (ItemStack stack : disabled) {
            event.remove(stack, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        }
    }
}
