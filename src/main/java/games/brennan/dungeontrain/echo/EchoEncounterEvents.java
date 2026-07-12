package games.brennan.dungeontrain.echo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

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
public final class EchoEncounterEvents {

    /** Scan cadence — matches the Echo Encounter advancement scan. */
    private static final int SCAN_PERIOD_TICKS = 10;

    private EchoEncounterEvents() {}

        public static void onAttackEntity(net.minecraft.world.entity.player.Player attacker, net.minecraft.world.entity.Entity attackTarget, boolean attackCanceled) {
        if (!(attacker instanceof ServerPlayer player)) return;
        Entity target = attackTarget;
        if (target == null) return;
        RemoteEchoEncounters.onPlayerStruckEcho(player, target.getUUID(), player.serverLevel().getGameTime());
    }

        public static void onLivingDamage(net.minecraft.world.entity.LivingEntity hurtEntity, net.minecraft.world.damagesource.DamageSource hitSource, float newDamage) {
        if (!(hurtEntity instanceof ServerPlayer player)) return; // victim is a player
        Entity source = hitSource.getEntity();
        if (source == null) return;
        RemoteEchoEncounters.onEchoStruckPlayer(player, source.getUUID());
    }

        public static void onDeath(net.minecraft.world.entity.LivingEntity deadEntity, net.minecraft.world.damagesource.DamageSource deathSource, boolean deathCanceled) {
        if (!(deadEntity.level() instanceof ServerLevel level)) return;
        RemoteEchoEncounters.onEntityDeath(level, deadEntity, deathSource.getEntity());
    }

        public static void onLevelTick(net.minecraft.world.level.Level tickedLevel) {
        if (!(tickedLevel instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (now % SCAN_PERIOD_TICKS != 0) return;
        RemoteEchoEncounters.scan(level, now);
    }

        public static void onServerStopping(net.minecraft.server.MinecraftServer server) {
        RemoteEchoEncounters.clearAll();
    }
}
