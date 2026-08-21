package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.joml.Vector3d;

import java.util.List;

/**
 * Reports a player's death to the relay when they die aboard a drifting carriage, so the build carries
 * the fact with it into whatever world leases it next. {@link SharedCarriageEnterEvents} reads it back
 * off the lease and tells the next traveller who ended there.
 *
 * <p>Its own subscriber rather than a branch inside {@link RunStatsEvents} or {@link DeathNoteEvents}:
 * those two own their own death pipelines (run statistics and the curse pool), and this is a third
 * destination with different gating. It resolves the carriage the same way
 * {@link SharedCarriageEnterEvents} does — footing, then ship-space, then the registry — because "the
 * carriage they died in" has to mean exactly what "the carriage they walked into" means.</p>
 *
 * <p>The report is fire-and-forget and sent immediately, NOT queued on {@link SharedCarriageEvents}'
 * flusher: a Dungeon Train death ends the world shortly afterwards, and a batched report would race
 * the lease return that tears the carriage down.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedCarriageDeathEvents {

    private SharedCarriageDeathEvents() {}

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (!SharedCarriageGate.canDiscover()) return;

        SharedCarriageRegistry.Instance inst = carriageUnder(level, player);
        if (inst == null) return;
        // A build with no relay identity has nowhere to record this. That is a fresh shared slot nobody
        // has edited yet: it uploads on its first real block change, and a death is not one.
        if (!inst.isOnRelay()) return;

        // Named only with the dying player's own network consent — the same gate their contributions
        // pass through. Without it the death still travels, unattributed: it happened, and the count is
        // not personal data.
        String name = SharedCarriageGate.canContribute(player) ? player.getGameProfile().getName() : "";
        String uuid = name.isEmpty() ? "" : player.getUUID().toString();

        SharedCarriageClient.death(inst.relayId(), inst.leaseToken(), uuid, name);
        // Fold it in locally too, so the log is already true in THIS world — the relay's copy only comes
        // back on a later lease, which for the player who just died is a different run entirely.
        inst.addLocalDeath(name);
    }

    /**
     * The drifting carriage the player is standing in, or null. Deliberately the same sequence
     * {@link SharedCarriageEnterEvents} polls with: footing under the player, world → ship space, then
     * the registry lookup.
     */
    private static SharedCarriageRegistry.Instance carriageUnder(ServerLevel level, ServerPlayer player) {
        List<Trains.Carriage> carriages = Trains.allCarriages(level);
        if (carriages.isEmpty()) return null;
        Trains.Carriage c = CarriageDeck.carriageUnder(carriages, player);
        if (c == null) return null;
        ManagedShip ship = c.ship();
        Vector3d local = new Vector3d(player.getX(), player.getY(), player.getZ());
        ship.worldToShip(local);
        return SharedCarriageRegistry.resolve(ship.subLevelId(),
                (int) Math.floor(local.x), (int) Math.floor(local.y), (int) Math.floor(local.z));
    }
}
