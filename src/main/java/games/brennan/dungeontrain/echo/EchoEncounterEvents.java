package games.brennan.dungeontrain.echo;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Wires gameplay signals into the remote-echo {@link RemoteEchoEncounters encounter journal}. Each
 * handler is unconditional and cheap: the manager looks up the echo's journal and no-ops when there
 * isn't one, so these never branch on whether a given entity is a tracked echo.
 *
 * <ul>
 *   <li>Spawn is delivered separately via the PlayerMob seam
 *       ({@code compat.PlayerMobSpawnBridge}); meeting / line-of-sight / crouch / on-off-train beats
 *       come from the periodic {@link #onLevelTick scan}.</li>
 *   <li>Combat: {@link AttackEntityEvent} (player → echo) and {@link LivingDamageEvent.Post}
 *       (echo → player).</li>
 *   <li>Kills + player death end the encounter via {@link LivingDeathEvent}.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EchoEncounterEvents {

    /** Scan cadence — matches the Echo Encounter advancement scan. */
    private static final int SCAN_PERIOD_TICKS = 10;

    private EchoEncounterEvents() {}

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (target == null) return;
        RemoteEchoEncounters.onPlayerStruckEcho(player, target.getUUID(), player.serverLevel().getGameTime());
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return; // victim is a player
        Entity source = event.getSource().getEntity();
        if (source == null) return;
        RemoteEchoEncounters.onEchoStruckPlayer(player, source.getUUID());
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        RemoteEchoEncounters.onEntityDeath(level, event.getEntity(), event.getSource().getEntity());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (now % SCAN_PERIOD_TICKS != 0) return;
        RemoteEchoEncounters.scan(level, now);
    }

        public static void onServerStopping(net.minecraft.server.MinecraftServer server) {
        RemoteEchoEncounters.clearAll();
    }
}
