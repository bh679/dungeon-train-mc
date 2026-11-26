package games.brennan.dungeontrain.perks.effects.lover;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Spare Enemies (Lover, tier 3): a hit of yours that leaves a hostile mob alive under the flee line
 * ({@link SpareEnemiesMath}) spares it. It pays the XP a kill would have, once, drops its target, runs
 * from you with Speed, and after {@link SpareEnemiesMath#FLEE_TICKS} (or once far away) vanishes. No
 * loot; it is not a kill. Bosses, PlayerMobs and Echoes are never spared.
 *
 * <p>The spared mark is on the mob's persistent data, so a mob saved and reloaded mid-flight vanishes
 * when it loads, and a spared mob killed while fleeing drops no XP a second time.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SpareEnemiesEffects {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String SPARED_TAG = DungeonTrain.MOD_ID + ":spared_by";

    /** A mob in flight: who spared it and the game time it vanishes. */
    private record Flight(Mob mob, UUID sparer, long vanishAt) {}

    private static final Map<UUID, Flight> FLEEING = new LinkedHashMap<>();

    private SpareEnemiesEffects() {}

    @SubscribeEvent
    public static void onDamaged(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        int rank = Perks.rank(player, LoverPerkIds.SPARE_ENEMIES);
        if (rank <= 0 || !canSpare(mob)) return;
        if (!SpareEnemiesMath.spares(mob.getHealth(), mob.getMaxHealth(), rank)) return;
        spare(mob, player);
    }

    /** Hostile, not a boss, not a PlayerMob or Echo, not already spared. */
    static boolean canSpare(Mob mob) {
        if (!(mob instanceof Enemy) || !mob.isAlive()) return false;
        if (mob instanceof PlayerMobEntity) return false;
        if (mob.getType().is(Tags.EntityTypes.BOSSES) || mob.getType() == EntityType.WARDEN
            || mob.getType() == EntityType.ELDER_GUARDIAN) return false;
        return !isSpared(mob);
    }

    static boolean isSpared(LivingEntity entity) {
        return entity.getPersistentData().contains(SPARED_TAG);
    }

    private static void spare(Mob mob, ServerPlayer player) {
        ServerLevel level = (ServerLevel) mob.level();
        int xp = EventHooks.getExperienceDrop(mob, player, mob.getExperienceReward(level, player));
        if (xp > 0) ExperienceOrb.award(level, mob.position(), xp);

        CompoundTag data = mob.getPersistentData();
        data.putUUID(SPARED_TAG, player.getUUID());
        mob.setTarget(null);
        mob.setAggressive(false);
        mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, SpareEnemiesMath.FLEE_TICKS, 0));
        FLEEING.put(mob.getUUID(), new Flight(mob, player.getUUID(), level.getGameTime() + SpareEnemiesMath.FLEE_TICKS));
        run(mob, player.position());
        LOGGER.debug("[DT-Perks] {} spared {} ({} xp)", player.getGameProfile().getName(), mob.getType(), xp);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (FLEEING.isEmpty()) return;
        Iterator<Flight> it = FLEEING.values().iterator();
        while (it.hasNext()) {
            Flight f = it.next();
            Mob mob = f.mob();
            if (!mob.isAlive() || mob.isRemoved()) {
                it.remove();
                continue;
            }
            ServerLevel level = (ServerLevel) mob.level();
            ServerPlayer sparer = level.getServer().getPlayerList().getPlayer(f.sparer());
            boolean far = sparer == null || sparer.level() != level
                || sparer.distanceToSqr(mob) > SpareEnemiesMath.VANISH_DISTANCE * SpareEnemiesMath.VANISH_DISTANCE;
            if (far || level.getGameTime() >= f.vanishAt()) {
                vanish(mob);
                it.remove();
                continue;
            }
            mob.setTarget(null);
            if (mob.tickCount % 10 == 0 || mob.getNavigation().isDone()) run(mob, sparer.position());
        }
    }

    /** Point the mob's path away from {@code from}. */
    private static void run(Mob mob, Vec3 from) {
        if (!(mob instanceof PathfinderMob walker)) return;
        Vec3 away = DefaultRandomPos.getPosAway(walker, 16, 7, from);
        if (away != null) walker.getNavigation().moveTo(away.x, away.y, away.z, 1.3);
    }

    private static void vanish(Mob mob) {
        if (mob.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.POOF, mob.getX(), mob.getY() + mob.getBbHeight() / 2, mob.getZ(),
                12, mob.getBbWidth() / 2, mob.getBbHeight() / 3, mob.getBbWidth() / 2, 0.02);
        }
        mob.discard();
    }

    /** A spared mob never picks a target again. */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() != null && event.getEntity() instanceof Mob mob && isSpared(mob)) {
            event.setCanceled(true);
        }
    }

    /** Its XP was paid when it was spared. */
    @SubscribeEvent
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        if (isSpared(event.getEntity())) event.setCanceled(true);
    }

    /** A spared mob saved mid-flight has fled by the time it loads again. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        if (isSpared(mob) && !FLEEING.containsKey(mob.getUUID())) event.setCanceled(true);
    }
}
