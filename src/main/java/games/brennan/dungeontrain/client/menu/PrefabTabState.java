package games.brennan.dungeontrain.client.menu;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;

/**
 * Client singleton — holds the variant + contents id lists synced from the
 * server on login. The two custom {@link CreativeModeTabs}-style tabs
 * registered in {@code ModCreativeTabs} read from these lists in their
 * {@code displayItems} lambdas.
 *
 * <p>When new ids arrive (sync packet), {@link #applyRegistry} forces a
 * tab-content rebuild so the tabs reflect the latest data immediately.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PrefabTabState {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static List<String> variantIds = Collections.emptyList();
    private static List<String> contentsIds = Collections.emptyList();

    private PrefabTabState() {}

    /**
     * Replace the cached registry and force the creative tabs to rebuild
     * their contents. Called from the sync packet handler.
     */
    public static void applyRegistry(List<String> variantIds, List<String> contentsIds) {
        PrefabTabState.variantIds = List.copyOf(variantIds);
        PrefabTabState.contentsIds = List.copyOf(contentsIds);
        rebuildTabsSafely();
    }

    /** Clear all state — called when the player disconnects from the server. */
    public static void clear() {
        variantIds = Collections.emptyList();
        contentsIds = Collections.emptyList();
        rebuildTabsSafely();
    }

    public static List<String> variantIds() {
        return variantIds;
    }

    public static List<String> contentsIds() {
        return contentsIds;
    }

    /**
     * Trigger {@link CreativeModeTabs#tryRebuildTabContents} so each tab's
     * {@code displayItems} lambda is re-invoked against the new state.
     * Wrapped defensively because connection / level state may be partially
     * unavailable in edge cases (e.g. during initial connect handshake).
     */
    private static void rebuildTabsSafely() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return;
        try {
            FeatureFlagSet flags = conn.enabledFeatures();
            boolean hasOpTabs = mc.player != null && mc.player.canUseGameMasterBlocks();
            CreativeModeTabs.tryRebuildTabContents(flags, hasOpTabs, conn.registryAccess());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Prefab tab rebuild skipped: {}", e.toString());
            // Fallback to vanilla flags so we at least try a refresh once
            // the registry data is in.
            try {
                CreativeModeTabs.tryRebuildTabContents(
                    FeatureFlags.DEFAULT_FLAGS, false, conn.registryAccess());
            } catch (Exception ignored) {
                // Best-effort; tabs will refresh next time vanilla itself
                // calls tryRebuildTabContents (e.g. on world join).
            }
        }
    }
}
