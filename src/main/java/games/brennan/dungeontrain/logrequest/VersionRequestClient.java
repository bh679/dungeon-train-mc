package games.brennan.dungeontrain.logrequest;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.SystemSpecCollector;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.VersionReportPacket;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.List;

/**
 * Client side of the Developer {@code /version} ping (see {@link VersionRequestManager}): answers
 * with this game's own versions, silently — nothing is shown and nothing is asked. The full answer
 * reuses {@link SystemSpecCollector}'s redacted spec (it reads GL strings, so this runs on the
 * render thread, where the packet handler enqueues it).
 */
public final class VersionRequestClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private VersionRequestClient() {}

    /** The server is asking which version this game runs. Client thread. */
    public static void onQuery(int id, boolean full) {
        try {
            String text = full
                    ? VersionReportText.full(VersionInfo.VERSION, VersionInfo.BRANCH, SystemSpecCollector.collect(), mods())
                    : VersionReportText.shortLine(VersionInfo.VERSION, VersionInfo.BRANCH);
            DungeonTrainNet.sendToServer(new VersionReportPacket(id, text));
        } catch (Exception ex) {
            LOGGER.warn("[DungeonTrain] Failed to answer version ping: {}", ex.toString());
        }
    }

    private static List<String> mods() {
        return ModList.get().getMods().stream()
                .map(m -> m.getModId() + "@" + m.getVersion())
                .sorted()
                .toList();
    }
}
