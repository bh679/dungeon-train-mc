package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.AdvancementsScreenAccessor;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Force {@code dungeontrain:dungeon_train/root} to be the default tab on
 * the {@link AdvancementsScreen}. Vanilla picks {@code tabs.entrySet()}
 * iteration order for the default when no tab is selected, and that map's
 * order is driven by network-sync iteration — not deterministic across
 * loads or worlds.
 *
 * <p>Approach: on every render of the advancements screen, if we haven't
 * already adjusted this screen instance, look up our root holder through
 * {@link AdvancementsScreenAccessor} and call
 * {@link AdvancementsScreen#onSelectedTabChanged} with it. Tracking is
 * per-screen via a {@link WeakHashMap} so subsequent user tab clicks are
 * respected — we only force the default once per screen instance.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DefaultAdvancementsTab {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation DUNGEON_TRAIN_ROOT_ID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/root");

    /**
     * Tracks which screen instances we've already adjusted. WeakHashMap so
     * closed screens GC normally; reopening creates a fresh instance which
     * gets re-adjusted on its next render.
     */
    private static final WeakHashMap<AdvancementsScreen, Boolean> ADJUSTED = new WeakHashMap<>();

    private DefaultAdvancementsTab() {}

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof AdvancementsScreen screen)) return;
        if (ADJUSTED.containsKey(screen)) return;
        Map<AdvancementHolder, AdvancementTab> tabs =
            ((AdvancementsScreenAccessor) screen).dungeontrain$getTabs();
        if (tabs.isEmpty()) return; // Tabs not synced yet — try again next frame.
        for (Map.Entry<AdvancementHolder, AdvancementTab> entry : tabs.entrySet()) {
            if (DUNGEON_TRAIN_ROOT_ID.equals(entry.getKey().id())) {
                screen.onSelectedTabChanged(entry.getKey());
                ADJUSTED.put(screen, Boolean.TRUE);
                LOGGER.debug("[DungeonTrain] Forced default advancements tab to {}",
                    DUNGEON_TRAIN_ROOT_ID);
                return;
            }
        }
        // Our tab isn't present (e.g. data pack removed it) — stop polling.
        ADJUSTED.put(screen, Boolean.TRUE);
    }
}
