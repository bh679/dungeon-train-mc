package games.brennan.dungeontrain.perks.effects.lover;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.net.AbandonRunPacket;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.slf4j.Logger;

/**
 * Save Ally (Lover, tier 3): a friend ({@link LoverFriends}) who takes a killing hit within
 * {@link SaveAllyMath#range} of a Lover with the perk ready is saved as a totem of undying would save
 * them. The wait is the Lover's ({@link SaveAllyMath#everyTicks}), not the friend's, kept on the
 * Lover's entity data so a new life starts ready. Never the Lover themself; never void, {@code /kill}
 * or abandon deaths; never by a Lover in Free Play. The nearest ready Lover saves.
 *
 * <p>HIGH priority, so a saved death is cancelled before DT's death reporters see it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SaveAllyEffects {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String READY_AT_TAG = DungeonTrain.MOD_ID + ":perk_save_ally_ready_at";
    private static final byte TOTEM_ENTITY_EVENT = 35;
    private static final float SAVED_HEALTH = 2.0F;
    private static final int REGEN_TICKS = 900;
    private static final int ABSORPTION_TICKS = 800;
    private static final int FIRE_RESISTANCE_TICKS = 800;

    private SaveAllyEffects() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) return;
        if (!canSave(event.getSource())) return;
        long now = level.getGameTime();
        ServerPlayer lover = nearestReadyLover(level, victim, now);
        if (lover == null) return;

        event.setCanceled(true);
        save(victim);
        int rank = Perks.rank(lover, LoverPerkIds.SAVE_ALLY);
        lover.getPersistentData().putLong(READY_AT_TAG, now + SaveAllyMath.everyTicks(rank));
        LOGGER.info("[DungeonTrain] Save Ally (rank {}) by {} saved {} from {}", rank,
            lover.getGameProfile().getName(), victim.getName().getString(), event.getSource().getMsgId());
    }

    /** What a real totem also refuses: void and {@code /kill}, and every DT abandon cause. */
    static boolean canSave(DamageSource source) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        return !AbandonRunPacket.isAbandonCause(source);
    }

    private static ServerPlayer nearestReadyLover(ServerLevel level, LivingEntity victim, long now) {
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            if (p == victim || !p.isAlive() || p.isSpectator()) continue;
            int rank = Perks.rank(p, LoverPerkIds.SAVE_ALLY);
            if (rank <= 0) continue;
            int range = SaveAllyMath.range(rank);
            double dist = p.distanceToSqr(victim);
            if (dist > (double) range * range || dist >= bestDist) continue;
            if (!isReady(p.getPersistentData(), now) || RunIntegrity.isCheated(p)) continue;
            if (!LoverFriends.isFriend(p, victim)) continue;
            best = p;
            bestDist = dist;
        }
        return best;
    }

    static boolean isReady(CompoundTag data, long now) {
        if (data == null || !data.contains(READY_AT_TAG)) return true;
        return now >= data.getLong(READY_AT_TAG);
    }

    /** As a totem of undying: one heart, effects cleared, Regeneration II, Absorption II, Fire Resistance. */
    private static void save(LivingEntity victim) {
        victim.setHealth(SAVED_HEALTH);
        victim.removeAllEffects();
        victim.addEffect(new MobEffectInstance(MobEffects.REGENERATION, REGEN_TICKS, 1));
        victim.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, ABSORPTION_TICKS, 1));
        victim.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, FIRE_RESISTANCE_TICKS, 0));
        victim.level().broadcastEntityEvent(victim, TOTEM_ENTITY_EVENT);
    }
}
