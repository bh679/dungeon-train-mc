package games.brennan.dungeontrain.cheat;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * A release build told to talk to a relay other than Dungeon Train's own is Free Play.
 *
 * <p>The relay address environment variables ({@link DungeonTrain#relayBaseUrl()},
 * {@link DungeonTrain#liveRelayBaseUrl()}) are a test seam for a local relay. In a release build
 * nobody needs them, and a relay of a player's own choosing can answer anything the game asks the
 * real one: inventory restores (which are also signed, see
 * {@link games.brennan.dungeontrain.net.relay.RestoreSignature}), cheat-mod and approved-mod lists,
 * debug grants. So the run is stamped at login, before any of those answers can land. Dev builds
 * are exempt: that is what the seam is for.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class RelayOverrideIntegrity {

    static final List<String> RELAY_ENV = List.of("DUNGEONTRAIN_RELAY_BASE_URL", "DUNGEONTRAIN_RELAY_LIVE_BASE_URL");

    private RelayOverrideIntegrity() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (taints(DungeonTrain.isDevBuild(), System::getenv)) {
            RunIntegrity.markCheated(player, Component.translatable("chat.dungeontrain.free_play.cause.relay_override"));
        }
    }

    /** Whether this build, reading {@code env}, points at a relay of the player's choosing. Pure, so it is pinned in a test. */
    static boolean taints(boolean devBuild, UnaryOperator<String> env) {
        if (devBuild) return false;
        for (String name : RELAY_ENV) {
            String value = env.apply(name);
            if (value != null && !value.isBlank()) return true;
        }
        return false;
    }
}
