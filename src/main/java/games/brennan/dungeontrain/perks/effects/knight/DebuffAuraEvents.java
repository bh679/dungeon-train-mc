package games.brennan.dungeontrain.perks.effects.knight;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Debuff Aura, server side. Once a second, for players with the perk: if the aura is ready and a
 * hostile is in range it turns on; while on, every hostile in range gets Weakness (and Slowness) for
 * {@value #EFFECT_TICKS} ticks, so the effect fades soon after a mob walks out. When it ends the
 * cooldown starts. State is in memory only: a relog or death simply leaves the aura ready.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DebuffAuraEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(DebuffAuraEvents.class);

    private static final int PULSE_TICKS = 20;
    /** A little over one pulse, so the effect never flickers off between pulses. */
    static final int EFFECT_TICKS = 40;
    private static final int RING_PARTICLES = 24;

    /** Game time the current aura ends at, then the game time it is ready again. */
    private record State(long activeUntil, long readyAt) {}

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private DebuffAuraEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % PULSE_TICKS != 0 || !player.isAlive() || player.isSpectator()) return;
        try {
            int rank = Perks.rank(player, DebuffAura.ID);
            if (rank <= 0) return;
            pulse(player, rank);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Debuff Aura failed for {}", player.getName().getString(), e);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    private static void pulse(ServerPlayer player, int rank) {
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        double range = DebuffAura.range(rank);
        List<LivingEntity> hostiles = level.getEntitiesOfClass(LivingEntity.class,
            player.getBoundingBox().inflate(range),
            e -> e instanceof Enemy && e.isAlive() && e.distanceToSqr(player) <= range * range);

        State state = STATES.get(player.getUUID());
        boolean active = state != null && now < state.activeUntil();
        if (!active) {
            boolean ready = state == null || now >= state.readyAt();
            if (!ready || hostiles.isEmpty()) return;
            long until = now + DebuffAura.activeTicks();
            STATES.put(player.getUUID(), new State(until, until + DebuffAura.cooldownTicks(rank)));
            ring(level, player, range);
        }
        int weakness = DebuffAura.weaknessLevel(rank);
        int slowness = DebuffAura.slownessLevel(rank);
        for (LivingEntity mob : hostiles) {
            mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, EFFECT_TICKS, weakness - 1), player);
            if (slowness > 0) {
                mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, EFFECT_TICKS, slowness - 1), player);
            }
        }
    }

    private static void ring(ServerLevel level, ServerPlayer player, double range) {
        double radius = Math.min(range, 3.0);
        for (int i = 0; i < RING_PARTICLES; i++) {
            double angle = Math.PI * 2 * i / RING_PARTICLES;
            level.sendParticles(ParticleTypes.WITCH, player.getX() + Math.cos(angle) * radius,
                player.getY() + 0.2, player.getZ() + Math.sin(angle) * radius, 1, 0, 0.1, 0, 0);
        }
    }
}
