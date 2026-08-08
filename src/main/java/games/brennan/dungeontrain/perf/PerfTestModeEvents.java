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
 * <p>The rules are applied through the command dispatcher rather than by poking
 * {@code GameRules.Key} constants directly. It reads exactly like the {@code /gamerule} lines a
 * human would type, keeps the rule names in {@link PerfTestMode#QUIET_GAME_RULES} as the single
 * source of truth, and avoids a second hard-coded list that could drift out of sync with it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PerfTestModeEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PerfTestModeEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!PerfTestMode.ENABLED) return;
        MinecraftServer server = event.getServer();
        for (String[] rule : PerfTestMode.QUIET_GAME_RULES) {
            String command = "gamerule " + rule[0] + " " + rule[1];
            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
            } catch (Exception e) {
                // A rule that some future MC version renames or drops shouldn't stop the server —
                // the benchmark is just slightly noisier, and the warning says which one to fix.
                LOGGER.warn("[DungeonTrain] Perf-test mode: '{}' failed: {}", command, e.toString());
            }
        }
        LOGGER.info("[DungeonTrain] Perf-test mode ON — seed={} and {} quiet game rules applied. "
                + "Flat terrain comes from the world preset (dungeontrain:dungeon_train_flat).",
            PerfTestMode.seed(), PerfTestMode.QUIET_GAME_RULES.length);
    }
}
