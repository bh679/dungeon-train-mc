package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.LootTableLoadEvent;

/**
 * Keeps Exposure's film and photographs out of vanilla chests.
 *
 * <p>Exposure appends its own {@code exposure:chests/*} tables to five vanilla chest tables through a
 * global loot modifier whose only off-switch is its config. DT hands the camera and film out through
 * its own container loot instead ({@code data/dungeontrain/containers}), so those tables are dropped
 * as they load and the modifier has nothing to append. Named by id only — nothing here links against
 * Exposure — and its block drops ({@code exposure:blocks/*}) are left alone.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ExposureLootSuppressor {

    static final String NAMESPACE = "exposure";
    static final String CHEST_PREFIX = "chests/";

    private ExposureLootSuppressor() {}

    @SubscribeEvent
    public static void onLootTableLoad(LootTableLoadEvent event) {
        if (isSuppressed(event.getName())) {
            event.setCanceled(true);
        }
    }

    /** True for the chest tables Exposure injects into vanilla loot. */
    public static boolean isSuppressed(ResourceLocation table) {
        return table != null
                && NAMESPACE.equals(table.getNamespace())
                && table.getPath().startsWith(CHEST_PREFIX);
    }
}
