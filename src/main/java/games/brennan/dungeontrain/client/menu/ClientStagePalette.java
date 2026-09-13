package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.StageIconPalettePacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Client cache of the effective stage's placeholder resolutions ({@link StageIconPalettePacket}),
 * read by the stage-aware item icons. Empty outside the editor.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ClientStagePalette {

    private static volatile String stageId = "";
    private static volatile Map<String, String> byName = Map.of();

    private ClientStagePalette() {}

    public static void apply(StageIconPalettePacket packet) {
        Map<String, String> next = new HashMap<>();
        for (StageIconPalettePacket.Entry e : packet.entries()) next.put(e.name(), e.blockId());
        byName = Map.copyOf(next);
        stageId = packet.stageId() == null ? "" : packet.stageId();
    }

    /** The effective stage id, or {@code ""} when none (outside the editor). */
    public static String stageId() {
        return stageId;
    }

    /** The block id placeholder {@code name} resolves to for the effective stage, or null. */
    public static String resolved(String name) {
        return byName.get(name);
    }

    public static void clear() {
        stageId = "";
        byName = Map.of();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }
}
