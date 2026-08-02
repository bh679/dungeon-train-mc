package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.train.SharedCarriageMessage;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Vector3d;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends a dark-gray chat hint the moment a player steps onto a shared carriage — a fresh-canvas line
 * for a locally-placed carriage, or a "someone was here" line for one leased from the relay pool
 * (distinguished by {@link SharedCarriageRegistry.Instance#leasedFromPool}). See
 * {@link SharedCarriageMessage}.
 *
 * <p>Detection polls each player's footing on a coarse cadence ({@link #CHECK_INTERVAL_TICKS}) and fires
 * only on a TRANSITION onto a new shared carriage (keyed by sub-level + carriage index), with a short
 * per-player cooldown so hopping a group boundary can't spam. Server-side only.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedCarriageEnterEvents {

    private static final int CHECK_INTERVAL_TICKS = 10;   // ~0.5 s
    private static final long MESSAGE_COOLDOWN_MS = 3000L;

    /** Per-player key ("subLevelId:pIdx") of the shared carriage they were last found standing on. */
    private static final Map<UUID, String> LAST_KEY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_MSG_MS = new ConcurrentHashMap<>();

    private SharedCarriageEnterEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player p = event.getEntity();
        if (p.level().isClientSide() || !(p instanceof ServerPlayer player)) return;
        if (!SharedCarriageGate.canDiscover()) return;
        if (player.tickCount % CHECK_INTERVAL_TICKS != 0) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        String key = null;
        boolean leased = false;
        List<Trains.Carriage> carriages = Trains.allCarriages(level);
        if (!carriages.isEmpty()) {
            Trains.Carriage c = CarriageDeck.carriageUnder(carriages, player);
            if (c != null) {
                ManagedShip ship = c.ship();
                Vector3d local = new Vector3d(player.getX(), player.getY(), player.getZ());
                ship.worldToShip(local);
                SharedCarriageRegistry.Instance inst = SharedCarriageRegistry.resolve(
                        ship.subLevelId(),
                        (int) Math.floor(local.x), (int) Math.floor(local.y), (int) Math.floor(local.z));
                if (inst != null) {
                    key = ship.subLevelId() + ":" + inst.pIdx;
                    leased = inst.leasedFromPool;
                }
            }
        }

        UUID id = player.getUUID();
        if (key == null) {
            LAST_KEY.remove(id); // left every shared carriage → a later re-entry messages again
            return;
        }
        if (key.equals(LAST_KEY.get(id))) return; // still on the same carriage
        LAST_KEY.put(id, key);

        long now = System.currentTimeMillis();
        Long last = LAST_MSG_MS.get(id);
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) return;
        LAST_MSG_MS.put(id, now);

        player.sendSystemMessage(leased
                ? SharedCarriageMessage.seenCarriage(level.getRandom())
                : SharedCarriageMessage.newCarriage(level.getRandom()));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        LAST_KEY.remove(id);
        LAST_MSG_MS.remove(id);
    }
}
