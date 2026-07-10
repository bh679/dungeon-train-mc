package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.DeathNoteEchoSpawner;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;

/**
 * Per-tick steering for live Death Note echoes: a marked echo abandons the normal forward march and
 * homes onto its target. Each scan it points the echo's train march direction at the target — the
 * {@code AdvanceCarriageGoal} yields once the echo acquires a target, and the 0-feeling
 * {@code Reaction.FIGHT}/{@code FLEE} drives the actual attack-or-flee. This is only a nudge toward
 * the player: the mob's own AI decides combat, honouring its trait (a weaker echo may flee).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DeathNoteEchoController {

    private static final int SCAN_PERIOD_TICKS = 10;
    /** Steer echoes within this range of their target; beyond it, leave them be. */
    private static final double STEER_RANGE = 96.0;
    /** Within this range, force the target so the echo engages even an invulnerable (Creative) player. */
    private static final double ENGAGE_RANGE = 12.0;

    private DeathNoteEchoController() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;
        for (ServerPlayer player : players) {
            String targetKey = player.getUUID().toString();
            List<PlayerMobEntity> nearby = level.getEntitiesOfClass(PlayerMobEntity.class,
                player.getBoundingBox().inflate(STEER_RANGE));
            for (PlayerMobEntity echo : nearby) {
                CompoundTag data = echo.getPersistentData();
                if (!data.contains(DeathNoteEchoSpawner.KEY_TARGET)) continue;
                if (!targetKey.equals(data.getString(DeathNoteEchoSpawner.KEY_TARGET))) continue;
                steerToward(echo, player);
            }
        }
    }

    /**
     * Nudge the echo toward its target: march its train direction at the target along X (approach
     * across carriages), and within melee-ish range force the target so the echo engages even an
     * invulnerable Creative player (vanilla target-selection skips invulnerable players). Flee-by-
     * trait is preserved — {@code FleeFromCategoryGoal} (higher priority) is reaction-driven, not
     * {@code getTarget}-driven, so a fleeing echo still flees despite the forced target.
     */
    private static void steerToward(PlayerMobEntity echo, ServerPlayer target) {
        int dir = (int) Math.signum(target.getX() - echo.getX());
        if (dir != 0) {
            try {
                TrainConfinement.setMarchDirection(echo, dir);
            } catch (Throwable ignored) {
                // best-effort steering; the target/attack goals still function without it
            }
        }
        if (echo.distanceToSqr(target) <= ENGAGE_RANGE * ENGAGE_RANGE) {
            echo.setTarget(target);
        }
    }
}
