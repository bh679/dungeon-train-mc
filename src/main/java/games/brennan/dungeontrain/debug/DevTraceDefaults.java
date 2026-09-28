package games.brennan.dungeontrain.debug;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.command.DebugCommand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Dev-build defaults for the train-generation diagnostics.
 *
 * <p>In a dev environment ({@code ./gradlew runClient} / {@code runServer}) the four probes behind
 * {@code /dungeontrain debug traingen on} are armed as soon as the server starts, so every test
 * ride lands {@code [bwdgen]} samples in {@code debug.log} without anybody typing the command. A
 * ride that has to be repeated because a probe was off is a wasted test. Production builds keep
 * the probes off; the command still toggles them either way.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DevTraceDefaults {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DevTraceDefaults() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (FMLEnvironment.production) return;
        DebugCommand.setTrainGenTraceProbes(event.getServer(), true);
        LOGGER.info("[DungeonTrain] Dev build: train-generation trace armed by default ([bwdgen] + stall detector + [seamgap]); '/dungeontrain debug traingen off' disables it");
    }
}
