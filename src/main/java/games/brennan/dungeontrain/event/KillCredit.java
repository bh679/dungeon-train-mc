package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who gets the kill. A death with a causing entity (sword, arrow, a mob's bite) credits that
 * entity, as before. A death that carries none (void, lava, fire, a fatal fall) credits whoever
 * last hurt the victim within {@link #KNOCKBACK_CREDIT_WINDOW_TICKS} — so knocking something off
 * the train into the void or into lava counts as your kill — and otherwise falls back to vanilla's
 * {@link LivingEntity#getKillCredit()}.
 *
 * <p>The window is our own rather than vanilla's 100-tick one because a fall from the train to the
 * void can outlast five seconds; it matches the echo void-push window in
 * {@link PlayerMobAdvancementEvents}. Strikes are not consumed on death: several
 * {@code LivingDeathEvent} handlers resolve the same death, so entries simply expire.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class KillCredit {

    /** How long after a hit an attacker-less death still credits the one who struck. */
    static final long KNOCKBACK_CREDIT_WINDOW_TICKS = 300L;

    /** How often (ticks) stale strikes are swept. */
    private static final int PRUNE_PERIOD_TICKS = 200;

    /** victim UUID → most recent hit by another entity. */
    private static final Map<UUID, Strike> LAST_STRIKES = new HashMap<>();

    record Strike(UUID attackerUuid, long tick) {}

    private KillCredit() {}

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) return;
        Entity attacker = event.getSource().getEntity();
        if (attacker == null || attacker == victim) return;
        LAST_STRIKES.put(victim.getUUID(), new Strike(attacker.getUUID(), level.getGameTime()));
    }

    /**
     * The entity credited with {@code victim}'s death from {@code source}: the source's causing
     * entity when it has one, else the player whose wither rose withered it
     * ({@link WitherRoseCredit}), else the last recent striker, else vanilla's kill credit.
     */
    public static Optional<Entity> resolveKiller(LivingEntity victim, DamageSource source) {
        Entity direct = source.getEntity();
        if (direct != null) return Optional.of(direct);
        Optional<Entity> rosePlacer = WitherRoseCredit.creditFor(victim, source);
        if (rosePlacer.isPresent()) return rosePlacer;
        Strike strike = LAST_STRIKES.get(victim.getUUID());
        if (strike != null && withinWindow(strike.tick(), victim.level().getGameTime())) {
            Entity striker = findEntity(victim.getServer(), strike.attackerUuid());
            if (striker != null && striker != victim) return Optional.of(striker);
        }
        return Optional.<Entity>ofNullable(victim.getKillCredit()).filter(e -> e != victim);
    }

    /** True when the death carries no causing entity, i.e. any credit is a knockback credit. */
    public static boolean isIndirect(DamageSource source) {
        return source.getEntity() == null;
    }

    static boolean withinWindow(long strikeTick, long now) {
        long age = now - strikeTick;
        return age >= 0 && age <= KNOCKBACK_CREDIT_WINDOW_TICKS;
    }

    private static Entity findEntity(MinecraftServer server, UUID uuid) {
        if (server == null) return null;
        Entity player = server.getPlayerList().getPlayer(uuid);
        if (player != null) return player;
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) return entity;
        }
        return null;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (LAST_STRIKES.isEmpty()) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % PRUNE_PERIOD_TICKS != 0) return;
        long now = server.overworld().getGameTime();
        LAST_STRIKES.values().removeIf(s -> !withinWindow(s.tick(), now));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST_STRIKES.clear();
    }
}
