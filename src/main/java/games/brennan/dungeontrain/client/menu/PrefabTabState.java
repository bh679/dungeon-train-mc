package games.brennan.dungeontrain.client.menu;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.PrefabRegistrySyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Client singleton — holds the variant + loot prefab entries synced from
 * the server on login. Each entry carries both the icon block (for the
 * creative tab grid item) and the full constituent list (for the icon-grid
 * tooltip preview).
 *
 * <p>Updates trigger {@link CreativeModeTabs#tryRebuildTabContents} so the
 * tab reflects the latest data immediately.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PrefabTabState {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static List<PrefabRegistrySyncPacket.VariantEntry> variantEntries = Collections.emptyList();
    private static List<PrefabRegistrySyncPacket.LootEntry> lootEntries = Collections.emptyList();

    private PrefabTabState() {}

    public static void applyRegistry(
        List<PrefabRegistrySyncPacket.VariantEntry> variants,
        List<PrefabRegistrySyncPacket.LootEntry> loot
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

    public static List<PrefabRegistrySyncPacket.VariantEntry> variantEntries() {
        return variantEntries;
    }

    public static List<PrefabRegistrySyncPacket.LootEntry> lootEntries() {
        return lootEntries;
    }

    /** Lookup: full block-id list for a variant prefab — used by the tooltip layer. */
    public static Optional<List<String>> findVariantBlocks(String prefabId) {
        if (prefabId == null) return Optional.empty();
        for (PrefabRegistrySyncPacket.VariantEntry e : variantEntries) {
            if (e.id().equals(prefabId)) return Optional.of(e.blockIds());
        }
        return Optional.empty();
    }

    /** Lookup: full item list for a loot prefab — used by the tooltip layer. */
    public static Optional<List<PrefabRegistrySyncPacket.LootItem>> findLootItems(String prefabId) {
        if (prefabId == null) return Optional.empty();
        for (PrefabRegistrySyncPacket.LootEntry e : lootEntries) {
            if (e.id().equals(prefabId)) return Optional.of(e.items());
        }
        return Optional.empty();
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
