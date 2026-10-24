package games.brennan.dungeontrain.perks.effects.tank;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tank tier 3 "Healing": half a heart every {@link TankPerks#healIntervalSeconds} seconds (60 at rank 0 is
 * never reached: rank 1 is 55 s, rank 11+ the 5 s floor). A per-player tick counter runs only while the
 * perk is active and the player is alive; it is not persisted, so a relog restarts the wait.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TankHealingPerk {

    private static final Map<UUID, Integer> TICKS_WAITED = new ConcurrentHashMap<>();

    private TankHealingPerk() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        int interval = TankPerks.healIntervalTicks(Perks.rank(player, TankPerks.HEALING));
        if (interval <= 0 || !player.isAlive() || player.isSpectator()) {
            TICKS_WAITED.remove(player.getUUID());
            return;
        }
        int waited = TICKS_WAITED.merge(player.getUUID(), 1, Integer::sum);
        if (waited < interval) return;
        TICKS_WAITED.put(player.getUUID(), 0);
        if (player.getHealth() < player.getMaxHealth()) player.heal(TankPerks.HEAL_AMOUNT);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TICKS_WAITED.remove(event.getEntity().getUUID());
    }
}
