package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.EnchiridionAdvancements;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PhotoAlbumReportPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tells the server, at login, which mobs this client keeps photos of in each shared mob collection, so
 * ticks copied between them need a photo behind them. See {@code compat/photo/SharedPhotoCriteria}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PhotoAlbumReportClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PhotoAlbumReportClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        try {
            if (Minecraft.getInstance().getConnection() == null) return;
            DungeonTrainNet.sendToServer(new PhotoAlbumReportPacket(report()));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] photo album report to server failed: {}", t.toString());
        }
    }

    private static Map<String, Set<String>> report() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String path : EnchiridionAdvancements.SHARED_PHOTO_LISTS) {
            Set<String> ids = new LinkedHashSet<>();
            for (EarnedPhotos.Entry e : EarnedPhotos.entries(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path))) {
                if (e.id() != null && e.file() != null) ids.add(e.id().toString());
            }
            out.put(path, Set.copyOf(ids));
        }
        return out;
    }
}
