package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.util.JavaRceCheck;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import org.slf4j.Logger;

/**
 * Logs whether the server's Java is exposed to CVE-2026-114938 ("Java4Shell"), see
 * {@link JavaRceCheck}. A dedicated server takes Base64 from every client that connects, so its
 * operator is the one who most needs the line, and its log is the only place they will look.
 * Fires on {@link ServerAboutToStartEvent} (integrated and dedicated alike); never throws.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class JavaRceServerCheck {

    private static final Logger LOGGER = LogUtils.getLogger();

    private JavaRceServerCheck() {}

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        try {
            JavaRceCheck.Verdict verdict = JavaRceCheck.current();
            if (verdict == JavaRceCheck.Verdict.AT_RISK) {
                LOGGER.warn("==================================================================");
                LOGGER.warn(JavaRceCheck.describe(verdict));
                LOGGER.warn("[DungeonTrain] Fix: add {} to this server's java command line"
                    + " (before -jar), or update Java to {} or newer.",
                    JavaRceCheck.JVM_ARGUMENT, JavaRceCheck.fixedVersionFor(Runtime.version().feature()));
                LOGGER.warn("==================================================================");
            } else {
                LOGGER.info(JavaRceCheck.describe(verdict));
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not check Java for {}: {}", JavaRceCheck.CVE, t.toString());
        }
    }
}
