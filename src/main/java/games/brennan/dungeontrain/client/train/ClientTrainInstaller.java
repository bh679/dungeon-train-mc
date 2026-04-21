package games.brennan.dungeontrain.client.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.TrainAssembler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.world.ClientShipWorld;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.HashSet;
import java.util.Set;

/**
 * Client-only: every client tick, scan loaded VS ships for Dungeon Train
 * ships (identified by {@link TrainAssembler#TRAIN_SLUG_PREFIX}) and install a
 * {@link ClientTrainTransformProvider} if one isn't already installed.
 *
 * <p>Rationale over a custom networking packet: the slug is a property of the
 * base {@code Ship} interface, which VS already syncs to clients. No new
 * networking channel, no packet schema to version. If slug sync turns out to
 * be unreliable we can switch to a marker packet — diagnostic logs below will
 * surface that case by showing "no trains seen" while the server has one.
 */
@Mod.EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public final class ClientTrainInstaller {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Track which ship ids we've installed a provider on. Avoids re-installing
    // every tick and avoids log spam.
    private static final Set<Long> installed = new HashSet<>();
    private static int diagnosticTickCounter = 0;

    private ClientTrainInstaller() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            // Disconnected or main menu: drop the installed set so next world gets fresh state.
            if (!installed.isEmpty()) {
                installed.clear();
            }
            return;
        }

        ClientShipWorld shipWorld;
        try {
            shipWorld = VSGameUtilsKt.getShipObjectWorld(level);
        } catch (Exception e) {
            // VS not ready yet (world loading, etc.). Try again next tick.
            return;
        }
        if (shipWorld == null) return;

        int seenCount = 0;
        int trainCount = 0;
        for (ClientShip ship : shipWorld.getLoadedShips()) {
            seenCount++;
            String slug = ship.getSlug();
            if (!TrainAssembler.isTrainSlug(slug)) continue;
            trainCount++;

            if (!installed.contains(ship.getId())) {
                ClientTrainTransformProvider provider = new ClientTrainTransformProvider(ship.getId());
                ship.setTransformProvider(provider);
                installed.add(ship.getId());
                LOGGER.info(
                    "[DungeonTrain:clientInstaller] Installed ClientTrainTransformProvider on shipId={} slug={}",
                    ship.getId(), slug
                );
            }
        }

        // Periodic audit log — fires every ~10 seconds (200 client ticks @ 20Hz).
        // Confirms slug sync is working and the installed set matches what's loaded.
        if ((diagnosticTickCounter++ % 200) == 0) {
            LOGGER.info(
                "[DungeonTrain:clientInstaller] scan: loadedShips={} trainShips={} installedIds={}",
                seenCount, trainCount, installed
            );
        }
    }
}
