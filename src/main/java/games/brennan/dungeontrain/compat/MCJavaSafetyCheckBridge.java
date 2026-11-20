package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.mcjavasafetycheck.api.MCJavaSafetyCheckApi;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

/**
 * Bridge into the bundled <b>MC Java Safety Check</b> sibling ({@code mcjavasafetycheck}): the
 * Java4Shell (CVE-2026-114938) warning card and server log line that used to live in DT
 * (PR #2754). The sibling does the checking and the card; DT only:
 * <ul>
 *   <li>names itself the card's sender, so DT players keep seeing "Dungeon Train: Java Safety
 *       Check" and "Dungeon Train will never ask for passwords or payment";</li>
 *   <li>hands over the "Don't show me again" a player gave DT's own copy
 *       ({@code javaRceWarningDismissedFor}), once, then clears DT's key.</li>
 * </ul>
 * Without the sibling (or with one whose API no longer links) this does nothing. Hard imports are
 * confined to the nested {@link Api} holder, which loads only once the mod is known to be present.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MCJavaSafetyCheckBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String MOD_ID = "mcjavasafetycheck";

    /** The sender name DT gives the card. A proper noun, never translated. */
    static final String SENDER_NAME = "Dungeon Train";

    private MCJavaSafetyCheckBridge() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (!ModList.get().isLoaded(MOD_ID)) return;
        try {
            Api.setSender(SENDER_NAME);
            String dismissed = ClientDisplayConfig.getJavaRceWarningDismissedFor();
            if (!dismissed.isEmpty()) {
                Api.adoptDismissal(dismissed);
                ClientDisplayConfig.setJavaRceWarningDismissedFor("");
                LOGGER.info("[DungeonTrain] Handed the Java warning dismissal ({}) to MC Java Safety Check", dismissed);
            }
        } catch (LinkageError e) {
            LOGGER.warn("[DungeonTrain] MC Java Safety Check API did not link; the card keeps its own name: {}", e.toString());
        }
    }

    /** The only place the sibling's classes are named. */
    private static final class Api {
        static void setSender(String name) {
            MCJavaSafetyCheckApi.setDefaultSenderName(name);
        }

        static void adoptDismissal(String runtimeVersion) {
            MCJavaSafetyCheckApi.adoptDismissal(runtimeVersion);
        }
    }
}
