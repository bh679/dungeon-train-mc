package games.brennan.dungeontrain.perks.effects.tank;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.net.AbandonRunPacket;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.slf4j.Logger;

/**
 * Tank tier 3 "Totem": the Totem of Undying's save without holding one, once per cooldown
 * ({@link TankPerks#totemCooldownMinutes}: 20 minutes at rank 0 is never reached, rank 1 is 19, rank 10+
 * the 10 minute floor). Runs at HIGH priority so a saved death is cancelled before any of DT's death
 * reporters (Death Note, Discord, echo capture, run stats) see it.
 *
 * <p>Never fires when the player is in Free Play ({@link RunIntegrity#isCheated}), for a damage source
 * that bypasses invulnerability (what a real totem also refuses: {@code /kill}, the void, and every DT
 * abandon-run cause, which are all tagged so), or for an abandon cause by name as a belt-and-braces check.
 * The cooldown is stored on the entity's persistent data as the game time it is ready again, so it is
 * per life: the tag is not in {@code PlayerPersisted} and dies with the entity on a real death.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TankTotemPerk {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String READY_AT_TAG = DungeonTrain.MOD_ID + ":perk_totem_ready_at";
    /** Vanilla's "totem of undying used" entity event id, which plays the client animation. */
    private static final byte TOTEM_ENTITY_EVENT = 35;

    private static final int REGEN_TICKS = 900;
    private static final int REGEN_AMPLIFIER = 1;
    private static final int ABSORPTION_TICKS = 100;
    private static final int ABSORPTION_AMPLIFIER = 1;
    private static final int FIRE_RESISTANCE_TICKS = 800;
    private static final float SAVED_HEALTH = 1.0F;

    private TankTotemPerk() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.isCanceled()) return;
        int rank = Perks.rank(player, TankPerks.TOTEM);
        if (rank <= 0) return;
        if (!canSave(player, event.getSource())) return;
        long now = player.level().getGameTime();
        if (!isReady(player.getPersistentData(), now)) return;

        event.setCanceled(true);
        save(player);
        long readyAt = now + TankPerks.totemCooldownTicks(rank);
        player.getPersistentData().putLong(READY_AT_TAG, readyAt);
        LOGGER.info("[DungeonTrain] Totem perk (rank {}) saved {} from {}; ready again in {} min",
            rank, player.getName().getString(), event.getSource().getMsgId(), TankPerks.totemCooldownMinutes(rank));
    }

    /** The perk never overrides Free Play, forced deaths, or anything a real totem would not stop. */
    static boolean canSave(ServerPlayer player, DamageSource source) {
        if (RunIntegrity.isCheated(player)) return false;
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        return !AbandonRunPacket.isAbandonCause(source);
    }

    /** True when no cooldown is recorded or the recorded ready time has passed. */
    static boolean isReady(CompoundTag data, long now) {
        if (data == null || !data.contains(READY_AT_TAG)) return true;
        return now >= data.getLong(READY_AT_TAG);
    }

    /** Mirror of vanilla's totem save: 1 HP, effects cleared, Regen II, Absorption II, Fire Resistance. */
    private static void save(ServerPlayer player) {
        player.setHealth(SAVED_HEALTH);
        player.removeAllEffects();
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, REGEN_TICKS, REGEN_AMPLIFIER));
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, ABSORPTION_TICKS, ABSORPTION_AMPLIFIER));
        player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, FIRE_RESISTANCE_TICKS, 0));
        player.level().broadcastEntityEvent(player, TOTEM_ENTITY_EVENT);
    }
}
