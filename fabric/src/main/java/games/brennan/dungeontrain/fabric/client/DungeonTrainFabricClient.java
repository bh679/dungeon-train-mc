package games.brennan.dungeontrain.fabric.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrainCommon;
import games.brennan.dungeontrain.fabric.event.FabricClientEventBridges;
import games.brennan.dungeontrain.fabric.event.FabricClientEvents;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;

/**
 * Fabric client entrypoint — the Fabric mirror of the NeoForge {@code DungeonTrainClient}
 * plus the {@code isClient()} block of the {@code DungeonTrain} constructor. Runs only on
 * the physical client, so every client-only handler class it (transitively) references
 * never classloads on a dedicated server.
 *
 * <p>Sequence mirrors NeoForge: common client init ({@code DungeonTrainCommon.initClient()}),
 * the client registration spine ({@link FabricClientEvents}) that wires converted client
 * handlers to {@code DtEvents}, then the client event bridges (which subscribe the real
 * Fabric client events), then the S2C client network receivers.</p>
 *
 * <p><b>Fabric-v1 gap:</b> the NeoForge mod-list "settings" screen ({@code IConfigScreenFactory})
 * and the DP on-demand survey client hook are not wired here — the former needs Mod Menu
 * (not a dependency), the latter is a DiscordPresence (sibling, absent) hook.</p>
 */
public final class DungeonTrainFabricClient implements ClientModInitializer {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitializeClient() {
        DungeonTrainCommon.initClient();
        FabricClientEvents.register();
        FabricClientEventBridges.register();
        FabricClientPayloads.registerClientReceivers();
        LOGGER.info("Dungeon Train (Fabric) client initialised");
    }
}
