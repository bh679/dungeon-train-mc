package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.HiddenGap;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Client copy of how many hidden advancements sit between each synced advancement and the one it is drawn
 * from, fed by {@link games.brennan.dungeontrain.net.AdvancementGapsPacket}. The compact layout gives a
 * marked link an empty column and the connector draws a "…" across it — see {@link HiddenGap}.
 *
 * <p>Client thread only. Cleared on logout; the server's first advancements packet after login is a
 * reset, which clears it again.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class AdvancementGapsClient {

    private static Map<ResourceLocation, Integer> gaps = Map.of();

    private AdvancementGapsClient() {}

    /** Whether the link into {@code id} gets a "…". */
    public static boolean isMarked(ResourceLocation id) {
        Integer skipped = id == null ? null : gaps.get(id);
        return skipped != null && HiddenGap.isMarked(skipped);
    }

    public static void apply(boolean reset, Map<ResourceLocation, Integer> update) {
        Map<ResourceLocation, Integer> next = reset ? new HashMap<>() : new HashMap<>(gaps);
        update.forEach((id, skipped) -> {
            if (skipped > 0) next.put(id, skipped);
            else next.remove(id);
        });
        gaps = Map.copyOf(next);
    }

    /** Drops counts for advancements the client no longer holds (removed from the tree). */
    public static void retainOnly(Predicate<ResourceLocation> stillPresent) {
        if (gaps.isEmpty()) return;
        Map<ResourceLocation, Integer> next = new HashMap<>(gaps);
        if (next.keySet().removeIf(id -> !stillPresent.test(id))) gaps = Map.copyOf(next);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        gaps = Map.of();
    }
}
