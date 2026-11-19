package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Hides {@link DisabledModContent} gear and ores from every creative tab and the creative search, and
 * by default hides those mods' own tabs outright — an emptied tab is not drawn — and keeps their
 * contents out of the search. The two halves are separate client settings
 * ({@link ClientDisplayConfig#isCreativeModBlockTabs()} and
 * {@link ClientDisplayConfig#isCreativeModBlocksInSearch()}); the gear rule ignores both. Mods with no tab of
 * their own ({@link DisabledModContent#isHiddenCreativeItem}) have their items pulled from vanilla's tabs under the
 * same two settings.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DisabledModCreativeTabs {

    private DisabledModCreativeTabs() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        boolean hiddenTab = DisabledModContent.isBiomeModCreativeTab(event.getTabKey().location());
        // The settings are client config; a dedicated server never draws a tab, so it keeps the defaults.
        boolean client = FMLEnvironment.dist == Dist.CLIENT;
        if (!(client && ClientDisplayConfig.isCreativeModBlockTabs())) {
            for (ItemStack stack : new ArrayList<>(event.getParentEntries())) {
                if (hiddenTab || isHiddenItem(stack)) {
                    event.remove(stack, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
                }
            }
        }
        if (!(client && ClientDisplayConfig.isCreativeModBlocksInSearch())) {
            for (ItemStack stack : new ArrayList<>(event.getSearchEntries())) {
                if (hiddenTab || isHiddenItem(stack)) {
                    event.remove(stack, CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY);
                }
            }
        }
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

    private static boolean isHiddenItem(ItemStack stack) {
        return DisabledModContent.isHiddenCreativeItem(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
