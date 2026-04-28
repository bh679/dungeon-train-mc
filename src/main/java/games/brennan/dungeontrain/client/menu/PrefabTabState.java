package games.brennan.dungeontrain.client.menu;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.PrefabRegistrySyncPacket;
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
 * Client singleton — holds the variant + loot prefab entries synced from
 * the server on login. Each entry is {@code (id, blockId)} so the creative
 * tab's {@code displayItems} lambda can build a vanilla {@code BlockItem}
 * stack with the right icon.
 *
 * <p>Updates trigger {@link CreativeModeTabs#tryRebuildTabContents} so the
 * tab reflects the latest data immediately.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PrefabTabState {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static List<PrefabRegistrySyncPacket.Entry> variantEntries = Collections.emptyList();
    private static List<PrefabRegistrySyncPacket.Entry> lootEntries = Collections.emptyList();

    private PrefabTabState() {}

    public static void applyRegistry(
        List<PrefabRegistrySyncPacket.Entry> variants,
        List<PrefabRegistrySyncPacket.Entry> loot
    ) {
        variantEntries = List.copyOf(variants);
        lootEntries = List.copyOf(loot);
        rebuildTabsSafely();
    }

    public static void clear() {
        variantEntries = Collections.emptyList();
        lootEntries = Collections.emptyList();
        rebuildTabsSafely();
    }

    public static List<PrefabRegistrySyncPacket.Entry> variantEntries() {
        return variantEntries;
    }

    public static List<PrefabRegistrySyncPacket.Entry> lootEntries() {
        return lootEntries;
    }

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
            try {
                CreativeModeTabs.tryRebuildTabContents(
                    FeatureFlags.DEFAULT_FLAGS, false, conn.registryAccess());
            } catch (Exception ignored) {
                // Best-effort — tabs will rebuild next time vanilla itself does.
            }
        }
    }
}
