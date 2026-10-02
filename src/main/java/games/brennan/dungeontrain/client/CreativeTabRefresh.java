package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.SessionSearchTrees;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.CreativeModeTabSearchRegistry;
import org.slf4j.Logger;

import java.util.List;

/**
 * Rebuilds every creative tab's contents now, for a setting that changes what the tabs hold.
 *
 * <p>Vanilla's {@code CreativeModeTabs.tryRebuildTabContents} only rebuilds when the feature flags,
 * the operator-tab permission or the registries changed, so a flipped client setting would
 * otherwise not show until the next login. {@link CreativeModeTab#buildContents} is the same call
 * it makes per tab — category tabs first, because the search tab is assembled from theirs.</p>
 */
public final class CreativeTabRefresh {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CreativeTabRefresh() {}

    public static void rebuild() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (conn == null || mc.player == null) return;
        try {
            CreativeModeTab.ItemDisplayParameters parameters = new CreativeModeTab.ItemDisplayParameters(
                conn.enabledFeatures(),
                mc.options.operatorItemsTab().get() && mc.player.canUseGameMasterBlocks(),
                conn.registryAccess());
            for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
                if (tab.getType() == CreativeModeTab.Type.CATEGORY) tab.buildContents(parameters);
            }
            for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
                if (tab.getType() != CreativeModeTab.Type.CATEGORY) tab.buildContents(parameters);
            }
            // The typed search reads an index built from the old contents — re-index every searchable
            // tab, as the creative screen does after a rebuild of its own.
            SessionSearchTrees searchTrees = conn.searchTrees();
            for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
                if (!tab.hasSearchBar()) continue;
                List<ItemStack> items = List.copyOf(tab.getDisplayItems());
                searchTrees.updateCreativeTooltips(
                    conn.registryAccess(), items, CreativeModeTabSearchRegistry.getNameSearchKey(tab));
                searchTrees.updateCreativeTags(items, CreativeModeTabSearchRegistry.getTagSearchKey(tab));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Creative tab rebuild failed; the tabs update at next login", e);
            return;
        }
        // An open creative inventory laid its tab buttons out from the old contents.
        if (mc.screen instanceof CreativeModeInventoryScreen screen) {
            screen.resize(mc, screen.width, screen.height);
        }
    }
}
