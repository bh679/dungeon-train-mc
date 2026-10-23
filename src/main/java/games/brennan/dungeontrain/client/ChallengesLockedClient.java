package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;

/**
 * Client mirror of the Challenges-tab advancements the server won't grant because this is
 * multiplayer ({@link games.brennan.dungeontrain.net.ChallengesLockedPacket}). The advancements screen
 * fades those tiles and swaps their red footer for the "single-player only" line, the same way it
 * shows a challenge lost for this life ({@link LifeDisqualificationClient}).
 *
 * <p>Client thread only. Cleared on logout so a solo world never inherits a server's lock.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ChallengesLockedClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Set<ResourceLocation> locked = Set.of();
    /** Bumped on every change so per-widget tooltip caches know to re-split. */
    private static int revision;

    private ChallengesLockedClient() {}

    public static boolean isLocked(ResourceLocation id) {
        return id != null && locked.contains(id);
    }

    public static int revision() {
        return revision;
    }

    public static void apply(List<ResourceLocation> ids) {
        locked = Set.copyOf(ids);
        revision++;
        LOGGER.info("[DungeonTrain] Challenges locked for multiplayer: {}", locked.size());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        locked = Set.of();
        revision++;
    }
}
