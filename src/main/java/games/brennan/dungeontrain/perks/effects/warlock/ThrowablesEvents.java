package games.brennan.dungeontrain.perks.effects.warlock;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Throwables ({@link WarlockPerks#THROWABLES}). A thrower with the perk makes snowballs, eggs and
 * wind charges hurt what they hit ({@link WarlockPerks#throwDamage}), and can throw slimeballs and
 * fire charges. A snowball also frostbites (frost overlay and Slowness I), a slimeball slows harder
 * (Slowness II), and a fire charge sets the target alight. The effects last
 * {@link WarlockPerks#throwEffectTicks}. Eggs still hatch chicks and wind charges still push.
 *
 * <p>Slimeballs and fire charges fly as a vanilla {@link Snowball} showing that item, so no new entity
 * exists. A thrown fire charge only ever burns the mob it hits; it never lights blocks, unlike the
 * dispenser's small fireball. Thrown at a block, a fire charge still does what vanilla does there
 * (lights it); only a throw into the air is new.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ThrowablesEvents {

    private static final float THROW_VELOCITY = 1.5f;
    private static final float THROW_INACCURACY = 1.0f;

    private ThrowablesEvents() {}

    /** A slimeball or fire charge used in the air: thrown, as a snowball would be. */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getItemStack();
        if (!stack.is(Items.SLIME_BALL) && !stack.is(Items.FIRE_CHARGE)) return;
        Player player = event.getEntity();
        if (Perks.rank(player, WarlockPerks.THROWABLES) <= 0) return;
        Level level = player.level();
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            stack.is(Items.FIRE_CHARGE) ? SoundEvents.FIRECHARGE_USE : SoundEvents.SNOWBALL_THROW,
            SoundSource.NEUTRAL, 0.5f, 0.4f / (level.getRandom().nextFloat() * 0.4f + 0.8f));
        if (!level.isClientSide) {
            Snowball thrown = new Snowball(level, player);
            thrown.setItem(stack.copyWithCount(1));
            thrown.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0f, THROW_VELOCITY, THROW_INACCURACY);
            level.addFreshEntity(thrown);
        }
        stack.consume(1, player);
        player.swing(event.getHand());
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
    }

    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (projectile.level().isClientSide) return;
        if (!(event.getRayTraceResult() instanceof EntityHitResult hit)) return;
        if (!(projectile.getOwner() instanceof ServerPlayer thrower)) return;
        Kind kind = kindOf(projectile);
        if (kind == null) return;
        int rank = Perks.rank(thrower, WarlockPerks.THROWABLES);
        if (rank <= 0) return;
        Entity target = hit.getEntity();
        if (!(target instanceof LivingEntity living) || target == thrower) return;

        float damage = WarlockPerks.throwDamage(rank);
        if (damage > 0f) living.hurt(thrower.damageSources().thrown(projectile, thrower), damage);
        int ticks = WarlockPerks.throwEffectTicks(rank);
        switch (kind) {
            case SNOWBALL -> {
                living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 0), thrower);
                frostbite(living, ticks);
            }
            case SLIME -> living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 1), thrower);
            case FIRE -> {
                if (!living.fireImmune()) living.igniteForTicks(Math.max(ticks, living.getRemainingFireTicks()));
            }
            case EGG, WIND -> { }
        }
    }

    /**
     * The frost overlay for {@code ticks}: frozen ticks melt two a tick, so twice the time, held one
     * short of fully frozen so it never adds freeze damage of its own.
     */
    private static void frostbite(LivingEntity living, int ticks) {
        if (!living.canFreeze()) return;
        int cap = living.getTicksRequiredToFreeze() - 1;
        living.setTicksFrozen(Math.max(living.getTicksFrozen(), Math.min(cap, ticks * 2)));
    }

    private enum Kind { SNOWBALL, SLIME, FIRE, EGG, WIND }

    private static Kind kindOf(Projectile projectile) {
        if (projectile instanceof Snowball snowball) {
            ItemStack item = snowball.getItem();
            if (item.is(Items.SLIME_BALL)) return Kind.SLIME;
            if (item.is(Items.FIRE_CHARGE)) return Kind.FIRE;
            return Kind.SNOWBALL;
        }
        if (projectile instanceof ThrownEgg) return Kind.EGG;
        if (projectile instanceof WindCharge) return Kind.WIND;
        return null;
    }
}
