package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity.FreePlayCause;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.List;

/**
 * Client-side cache of why this run is in Free Play, as last pushed by
 * {@link games.brennan.dungeontrain.net.FreePlayCausePacket}. Read by
 * {@link FreePlayTooltip} when the player hovers the effect.
 *
 * <p>Replaced wholesale on every push (never mutated in place), and dropped on disconnect so one
 * world's reason can't be shown against the next world's badge.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class FreePlayCauseClient {

    private static volatile List<FreePlayCause> causes = List.of();

    private FreePlayCauseClient() {}

    /** Server push: the run's current reasons, or empty when it is no longer Free Play. */
    public static void set(List<FreePlayCause> incoming) {
        causes = List.copyOf(incoming);
    }

    /** The reasons to show, newest server answer first-hand. Empty when unknown or not Free Play. */
    public static List<FreePlayCause> causes() {
        return causes;
    }

    /** Drop the cached reasons on disconnect so they don't leak across sessions. */
    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        causes = List.of();
    }
}
