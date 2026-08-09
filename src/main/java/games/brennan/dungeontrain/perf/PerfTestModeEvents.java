package games.brennan.dungeontrain.perf;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Applies {@link PerfTestMode#QUIET_GAME_RULES} once the server is up, when perf-test mode is on.
 *
 * <p>Hooked to {@code ServerStartedEvent} rather than to world creation because that single hook
 * covers both shapes a benchmark runs in: the integrated server behind a dev quick-world, and a
 * dedicated server whose world was created from {@code server.properties}. It is also idempotent —
 * re-applying a rule that is already set is a no-op — so it is safe on a world that has been
 * launched in perf mode before.</p>
 *
 * <p>The rule set itself lives in {@link PerfTestMode#applyQuietRules}, shared with the dev
 * title-screen button that bakes the same rules into a new world at creation — one typed
 * implementation, so the two entry points cannot drift apart.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PerfTestModeEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PerfTestModeEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!PerfTestMode.ENABLED) return;
        MinecraftServer server = event.getServer();
        PerfTestMode.applyQuietRules(server.getGameRules(), server);
        LOGGER.info("[DungeonTrain] Perf-test mode ON — seed={} and {} quiet game rules applied. "
                + "Flat terrain comes from the world preset (dungeontrain:dungeon_train_flat).",
            PerfTestMode.seed(), PerfTestMode.QUIET_RULE_COUNT);
    }
}
