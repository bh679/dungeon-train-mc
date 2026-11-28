package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>That's What Friends Are For</b>: kill a monster that was attacking a passenger (a PlayerMob).
 *
 * <p>Reading the dying mob's {@code getTarget()} alone misses most real defences. Hitting the mob
 * makes it retarget the player, and a mob knocked off the train into the void loses its target
 * once it falls past follow range. So the mob's aggression towards a PlayerMob is remembered
 * for {@link #DEFENCE_WINDOW_TICKS}: when it targets one, and when it damages one (an arrow
 * resolves to its shooter).</p>
 *
 * <p>Credit follows {@link KillCredit#resolveKiller}, with one addition for this advancement only.
 * When the death has no causing entity (void, lava, a fall) and the shared credit went to someone
 * other than a player (typically the PlayerMob landing a hit after the player's shove), the last
 * player to strike the mob inside the window is the defender. A PlayerMob's own direct kill stays
 * its own. {@code KillCredit} itself is unchanged: other death handlers share it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PassengerDefenceEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long after going for a passenger a mob's death still counts as a defence. */
    static final long DEFENCE_WINDOW_TICKS = 300L;

    /** How often (ticks) stale entries are swept. */
    private static final int PRUNE_PERIOD_TICKS = 200;

    /** mob UUID → last tick it targeted or damaged a PlayerMob. */
    private static final Map<UUID, Long> AGGRESSION = new HashMap<>();

    /** victim UUID → most recent player strike. */
    private static final Map<UUID, PlayerStrike> PLAYER_STRIKES = new HashMap<>();

    record PlayerStrike(UUID playerUuid, long tick) {}

    private PassengerDefenceEvents() {}

    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity mob = event.getEntity();
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (!(mob instanceof Mob) || PlayerMobAdvancementEvents.isPlayerMob(mob)) return;
        if (!PlayerMobAdvancementEvents.isPlayerMob(event.getNewAboutToBeSetTarget())) return;
        AGGRESSION.put(mob.getUUID(), level.getGameTime());
    }

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) return;
        Entity attacker = event.getSource().getEntity();
        if (attacker == null || attacker == victim) return;
        long now = level.getGameTime();
        if (attacker instanceof ServerPlayer player) {
            PLAYER_STRIKES.put(victim.getUUID(), new PlayerStrike(player.getUUID(), now));
        } else if (attacker instanceof Mob
                && !PlayerMobAdvancementEvents.isPlayerMob(attacker)
                && PlayerMobAdvancementEvents.isPlayerMob(victim)) {
            AGGRESSION.put(attacker.getUUID(), now);
        }
    }

    @SubscribeEvent
    public static void onMobKilled(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) return;
        UUID id = victim.getUUID();
        Long aggression = AGGRESSION.remove(id);
        PlayerStrike strike = PLAYER_STRIKES.remove(id);
        if (!(victim instanceof Mob mob)) return;
        long now = level.getGameTime();
        boolean targetIsPlayerMob = PlayerMobAdvancementEvents.isPlayerMob(mob.getTarget());
        if (!wasAttackingPassenger(targetIsPlayerMob, aggression, now)) return;

        Entity killer = KillCredit.resolveKiller(victim, event.getSource()).orElse(null);
        UUID resolved = killer instanceof ServerPlayer ? killer.getUUID() : null;
        boolean indirect = KillCredit.isIndirect(event.getSource());
        Optional<UUID> defender = defender(resolved, indirect, strike, now);
        ServerPlayer player = defender
            .map(uuid -> level.getServer().getPlayerList().getPlayer(uuid))
            .orElse(null);
        LOGGER.debug("[DungeonTrain] Passenger defence: {} died ({}) liveTarget={} aggressionAge={} "
                + "killer={} lastPlayerStrikeAge={} -> {}",
            victim.getName().getString(), event.getSource().getMsgId(), targetIsPlayerMob,
            aggression == null ? "-" : now - aggression,
            killer == null ? "-" : killer.getName().getString(),
            strike == null ? "-" : now - strike.tick(),
            player == null ? "no defender" : "credit " + player.getName().getString());
        if (player != null) ModAdvancementTriggers.DEFENDED_PLAYERMOB.get().trigger(player);
    }

    /**
     * Was the dying mob attacking a passenger? Pure, for {@code PassengerDefenceTest}.
     *
     * @param lastAggressionTick last tick it targeted or damaged a PlayerMob, or {@code null}
     */
    static boolean wasAttackingPassenger(boolean targetIsPlayerMob, Long lastAggressionTick, long now) {
        return targetIsPlayerMob
            || (lastAggressionTick != null && withinWindow(lastAggressionTick, now));
    }

    /**
     * The player credited with the defence. Pure, for {@code PassengerDefenceTest}.
     *
     * @param resolvedPlayerKiller {@link KillCredit#resolveKiller}'s result when it is a player
     * @param indirectDeath        the death has no causing entity (void, lava, a fall)
     * @param lastPlayerStrike     the last player hit on the mob, or {@code null}
     */
    static Optional<UUID> defender(UUID resolvedPlayerKiller, boolean indirectDeath,
                                   PlayerStrike lastPlayerStrike, long now) {
        if (resolvedPlayerKiller != null) return Optional.of(resolvedPlayerKiller);
        if (!indirectDeath || lastPlayerStrike == null) return Optional.empty();
        if (!withinWindow(lastPlayerStrike.tick(), now)) return Optional.empty();
        return Optional.of(lastPlayerStrike.playerUuid());
    }

    private static boolean withinWindow(long tick, long now) {
        long age = now - tick;
        return age >= 0 && age <= DEFENCE_WINDOW_TICKS;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % PRUNE_PERIOD_TICKS != 0) return;
        if (AGGRESSION.isEmpty() && PLAYER_STRIKES.isEmpty()) return;
        long now = server.overworld().getGameTime();
        AGGRESSION.values().removeIf(t -> !withinWindow(t, now));
        PLAYER_STRIKES.values().removeIf(s -> !withinWindow(s.tick(), now));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        AGGRESSION.clear();
        PLAYER_STRIKES.clear();
    }
}
