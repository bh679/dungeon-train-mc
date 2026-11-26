package games.brennan.dungeontrain.perks.effects.warlock;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Convert Mob ({@link WarlockPerks#CONVERT_MOB}): sneak and right-click a hostile mob with an empty
 * hand. For {@link WarlockPerks#convertTicks} it fights hostile mobs within
 * {@link WarlockPerks#CONVERT_FIGHT_RANGE} and never targets a player, wearing purple sparks, then
 * turns back. One converted mob per player at a time; a new one releases the last. Then the perk rests
 * for {@link WarlockPerks#convertCooldownTicks}.
 *
 * <p>Bosses, raid captains, PlayerMobs (Echoes included) and tamed mobs can't be converted. The mob
 * carries its conversion in its persistent data ({@link #TAG}: owner, end time), so it survives a save;
 * live conversions are also kept in {@link #ACTIVE} for the once-a-second retarget pass.</p>
 *
 * <p>The cooldown and the last converted mob are top-level keys of the player's persistent data, so
 * they reset each life, as {@code RestockVillager}'s do.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ConvertMobEvents {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String TAG = "dungeontrain:converted";
    static final String COOLDOWN_KEY = "dungeontrain:convert_mob_ready_at";
    static final String LAST_KEY = "dungeontrain:convert_mob_last";
    static final String KEY_COOLDOWN = "gui.dungeontrain.warlock.ready_in";
    static final String KEY_IMMUNE = "gui.dungeontrain.warlock.convert.immune";
    static final String KEY_DONE = "gui.dungeontrain.warlock.convert.done";
    private static final int RETARGET_EVERY = 20;

    /** Converted mob UUID to its level, for the retarget pass. */
    private static final Map<UUID, ServerLevel> ACTIVE = new ConcurrentHashMap<>();

    private ConvertMobEvents() {}

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            if (isAttempt(event.getEntity(), event.getTarget(), event.getHand())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }
        if (!isAttempt(player, event.getTarget(), event.getHand())) return;
        Mob mob = (Mob) event.getTarget();
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!convertible(mob)) {
            player.displayClientMessage(Component.translatable(KEY_IMMUNE, mob.getDisplayName()), true);
            return;
        }
        int rank = Perks.rank(player, WarlockPerks.CONVERT_MOB);
        long now = player.server.overworld().getGameTime();
        long left = cooldownLeft(player, rank, now);
        if (left > 0) {
            player.displayClientMessage(Component.translatable(KEY_COOLDOWN, Component.translatable("gui.dungeontrain.perks.stat.unit.seconds", (left + 19) / 20)), true);
            return;
        }
        convert(player, mob, rank, now);
    }

    /** Sneaking, empty main hand, at a hostile mob, with the perk. */
    private static boolean isAttempt(Player player, Entity target, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown() && player.getMainHandItem().isEmpty()
            && target instanceof Mob && target instanceof Enemy && target.isAlive()
            && Perks.rank(player, WarlockPerks.CONVERT_MOB) > 0;
    }

    /** Not a boss, a raid captain, a PlayerMob or someone's pet. */
    static boolean convertible(Mob mob) {
        if (mob.getType().is(Tags.EntityTypes.BOSSES)) return false;
        if (mob instanceof WitherBoss || mob instanceof EnderDragon || mob instanceof Warden || mob instanceof ElderGuardian) {
            return false;
        }
        if (mob instanceof Raider raider && raider.isCaptain()) return false;
        if (mob instanceof PlayerMobEntity) return false;
        return !(mob instanceof OwnableEntity owned && owned.getOwnerUUID() != null);
    }

    /** Ticks left before the player may convert again; a stale time from another world is capped. */
    static long cooldownLeft(ServerPlayer player, int rank, long now) {
        long readyAt = player.getPersistentData().getLong(COOLDOWN_KEY);
        long left = readyAt - now;
        if (left <= 0) return 0;
        return Math.min(left, WarlockPerks.convertCooldownTicks(Math.max(1, rank)));
    }

    private static void convert(ServerPlayer player, Mob mob, int rank, long now) {
        CompoundTag data = player.getPersistentData();
        if (data.hasUUID(LAST_KEY)) release(player.server, data.getUUID(LAST_KEY));
        CompoundTag tag = new CompoundTag();
        tag.putUUID("owner", player.getUUID());
        tag.putLong("until", now + WarlockPerks.convertTicks(rank));
        mob.getPersistentData().put(TAG, tag);
        ACTIVE.put(mob.getUUID(), (ServerLevel) mob.level());
        data.putUUID(LAST_KEY, mob.getUUID());
        data.putLong(COOLDOWN_KEY, now + WarlockPerks.convertCooldownTicks(rank));
        mob.setTarget(null);
        mob.setLastHurtByMob(null);
        sparks(mob, 20);
        player.displayClientMessage(Component.translatable(KEY_DONE, mob.getDisplayName()), true);
        LOGGER.debug("[DT-Perks] convert-mob rank {} by {}: {} for {} t", rank, player.getScoreboardName(),
            mob.getName().getString(), WarlockPerks.convertTicks(rank));
    }

    /** Turns a converted mob back now, wherever it is loaded. */
    private static void release(net.minecraft.server.MinecraftServer server, UUID id) {
        ServerLevel level = ACTIVE.remove(id);
        if (level == null) return;
        if (level.getEntity(id) instanceof Mob mob) turnBack(mob);
    }

    private static void turnBack(Mob mob) {
        mob.getPersistentData().remove(TAG);
        ACTIVE.remove(mob.getUUID());
        mob.setTarget(null);
    }

    /** True while {@code entity} fights for someone. */
    static boolean isConverted(Entity entity, long now) {
        CompoundTag tag = entity.getPersistentData().getCompound(TAG);
        return !tag.isEmpty() && tag.getLong("until") > now;
    }

    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity mob = event.getEntity();
        if (mob.level().isClientSide) return;
        if (!isConverted(mob, mob.getServer().overworld().getGameTime())) return;
        LivingEntity next = event.getNewAboutToBeSetTarget();
        if (next instanceof Player || next != null && isConverted(next, mob.getServer().overworld().getGameTime())) {
            event.setCanceled(true);
        }
    }

    /** A converted mob loaded back from disk rejoins the retarget pass. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        if (mob.getPersistentData().contains(TAG)) ACTIVE.put(mob.getUUID(), (ServerLevel) event.getLevel());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) return;
        long now = event.getServer().overworld().getGameTime();
        if (now % RETARGET_EVERY != 0) return;
        for (Map.Entry<UUID, ServerLevel> e : Map.copyOf(ACTIVE).entrySet()) {
            Entity entity = e.getValue().getEntity(e.getKey());
            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                // unloaded keeps its tag and rejoins on load; dead is done
                ACTIVE.remove(e.getKey());
                continue;
            }
            if (!isConverted(mob, now)) {
                turnBack(mob);
                continue;
            }
            retarget(mob, now);
            sparks(mob, 3);
        }
    }

    /** Keeps a converted mob on the nearest hostile in range, never on a player or a fellow convert. */
    private static void retarget(Mob mob, long now) {
        LivingEntity current = mob.getTarget();
        if (current != null && current.isAlive() && !(current instanceof Player) && !isConverted(current, now)) return;
        double range = WarlockPerks.CONVERT_FIGHT_RANGE;
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity other : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(range),
                o -> o != mob && o.isAlive() && o instanceof Enemy && !isConverted(o, now))) {
            double d = mob.distanceToSqr(other);
            if (d < bestDist && d <= range * range) {
                bestDist = d;
                best = other;
            }
        }
        mob.setTarget(best);
    }

    private static void sparks(Mob mob, int count) {
        if (mob.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.WITCH, mob.getX(), mob.getY() + mob.getBbHeight() * 0.8, mob.getZ(),
                count, mob.getBbWidth() * 0.4, mob.getBbHeight() * 0.3, mob.getBbWidth() * 0.4, 0.0);
        }
    }
}
