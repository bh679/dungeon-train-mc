package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;

/**
 * Client mirror of {@link games.brennan.dungeontrain.advancement.LifeDisqualification}: the set of
 * advancements the server says are ruled out for the local player's current life.
 *
 * <p>Fed by {@link games.brennan.dungeontrain.net.LifeDisqualifiedPacket}. The advancements screen
 * reads {@link #isDisqualified} to grey a tile and add the red hint line; when the packet names
 * advancements that were <em>just</em> lost, any the player is tracking
 * ({@link TrackedAdvancements}) raise a {@link DisqualifiedAdvancementToast}.</p>
 *
 * <p>Client thread only. Cleared on logout so a second world never inherits the first's state
 * (the server resyncs on login anyway).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LifeDisqualificationClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Set<ResourceLocation> disqualified = Set.of();
    /** Bumped on every apply so per-widget tooltip caches know to re-split. */
    private static int revision;

    private LifeDisqualificationClient() {}

    public static boolean isDisqualified(ResourceLocation id) {
        return id != null && disqualified.contains(id);
    }

    public static int revision() {
        return revision;
    }

    /**
     * Replace the mirror with the server's full set; toast any newly-lost advancement being tracked,
     * and any tracked streak advancement whose count just restarted.
     */
    public static void apply(List<ResourceLocation> all, List<ResourceLocation> newlyLost,
                             List<ResourceLocation> streakReset) {
        disqualified = Set.copyOf(all);
        revision++;
        LOGGER.info("[DungeonTrain] Life-disqualified advancements: {} (newly lost: {}, streak reset: {})",
            all, newlyLost, streakReset);
        for (ResourceLocation id : newlyLost) {
            if (TrackedAdvancements.isTracked(id)) {
                LOGGER.info("[DungeonTrain] Tracked advancement {} lost this life — showing toast", id);
                showToast(id, DisqualifiedAdvancementToast.Kind.LOST);
            }
        }
        for (ResourceLocation id : streakReset) {
            if (TrackedAdvancements.isTracked(id)) {
                LOGGER.info("[DungeonTrain] Tracked advancement {} streak reset — showing toast", id);
                showToast(id, DisqualifiedAdvancementToast.Kind.STREAK_RESET);
            }
        }
    }

    private static void showToast(ResourceLocation id, DisqualifiedAdvancementToast.Kind kind) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return;
        AdvancementHolder holder = connection.getAdvancements().get(id);
        if (holder == null) return; // not synced to this client (tree stripped) — nothing to show
        mc.getToasts().addToast(new DisqualifiedAdvancementToast(holder, kind));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        disqualified = Set.of();
        revision++;
    }
}
