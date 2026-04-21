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

            // v0.10.5: client-side provider DISABLED. The 0.10.4 logs showed
            // VS reuses provideNextTransform's output as the next call's
            // `current` input. Since our provider always returned
            // forcedPos = incomingPos (when PIM delta is 0), and PIM delta
            // is always 0 on subsequent calls (because we already clamped
            // it), forcedPos got frozen at the first-tick value. The client
            // rendered the train stuck at its spawn position while server
            // canonicalPos advanced — user-visible "train isn't moving".
            //
            // The server-side PIM force (v0.10.4 TrainTransformProvider change)
            // is sufficient on its own: the server pushes a transform with
            // stable PIM=lockedPIM and linearly-advancing position=canonicalPos,
            // which the client receives and renders as-is. No render-path
            // intercept needed.
            //
            // Keeping the scan/log/slug logic for now as diagnostic and as
            // a hook if we later find a non-self-loop client-side fix is needed.
            if (!installed.contains(ship.getId())) {
                installed.add(ship.getId());
                LOGGER.info(
                    "[DungeonTrain:clientInstaller] Detected train shipId={} slug={} (client-side provider disabled in v0.10.5)",
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
