package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Steers live Death Note echoes onto their target each tick: it marches the echo along the train
 * toward the target's carriage and, once in the same/adjacent carriage, forces the target so the echo
 * engages even an invulnerable (Creative/Spectator) player — vanilla target-selection skips those, but
 * the curse should not. Flee-by-trait is preserved: {@code FleeFromCategoryGoal} (higher priority) is
 * reaction-driven, not {@code getTarget}-driven, so a fleeing echo still flees despite a forced target.
 *
 * <p>Echoes are tracked by UUID (registered at spawn) rather than a spatial scan, because a
 * carriage-bound echo lives in Sable shipyard coordinates far from the player's world position — a
 * world-space AABB around the player would never find it. Steering compares carriage indices
 * ({@link TrainConfinement#carriageIndex}), a frame the echo and the player share.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DeathNoteEchoController {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SCAN_PERIOD_TICKS = 10;

    /** echo entity UUID → the target player UUID it hunts. Registered at spawn, cleared on echo death. */
    private static final Map<UUID, UUID> ACTIVE = new ConcurrentHashMap<>();

    private DeathNoteEchoController() {}

    /** Called by {@code DeathNoteEchoSpawner} once the echo is added to the world. */
    public static void register(UUID echoUuid, UUID targetUuid) {
        if (echoUuid != null && targetUuid != null) ACTIVE.put(echoUuid, targetUuid);
    }

    /** Called by {@code DeathNoteEvents} when a death-note echo dies. */
    public static void unregister(UUID echoUuid) {
        if (echoUuid != null) ACTIVE.remove(echoUuid);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (ACTIVE.isEmpty()) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;
        for (Map.Entry<UUID, UUID> e : ACTIVE.entrySet()) {
            Entity ent = level.getEntity(e.getKey());
            if (!(ent instanceof PlayerMobEntity echo)) {                    // other level / unloaded / culled
                LOGGER.info("[DN-DEBUG] controller: echo {} NOT resolvable in level (got {}).",
                        e.getKey(), ent == null ? "null" : ent.getClass().getSimpleName());
                continue;
            }
            if (!echo.isAlive()) {
                LOGGER.info("[DN-DEBUG] controller: echo {} is DEAD — dropping.", e.getKey());
                ACTIVE.remove(e.getKey());
                continue;
            }
            ServerPlayer target = level.getServer().getPlayerList().getPlayer(e.getValue());
            if (target == null) continue;                                    // target offline
            LOGGER.info("[DN-DEBUG] controller: echo {} at ({},{},{}) carriageIdx={} | target {} at ({},{},{}) carriageIdx={}",
                    e.getKey(),
                    String.format("%.1f", echo.getX()), String.format("%.1f", echo.getY()), String.format("%.1f", echo.getZ()),
                    TrainConfinement.carriageIndex(echo),
                    target.getGameProfile().getName(),
                    String.format("%.1f", target.getX()), String.format("%.1f", target.getY()), String.format("%.1f", target.getZ()),
                    TrainConfinement.carriageIndex(target));
            steer(echo, target);
        }
    }

    /**
     * March the echo toward the target's carriage and force the target once alongside. Uses carriage
     * indices (a frame the echo and the player share), NOT raw world coords — the echo is in shipyard
     * space and the player in world space, so subtracting {@code getX()} would mix frames.
     */
    private static void steer(PlayerMobEntity echo, ServerPlayer target) {
        int echoIdx = TrainConfinement.carriageIndex(echo);
        int targetIdx = TrainConfinement.carriageIndex(target);
        if (echoIdx == TrainConfinement.NO_CARRIAGE || targetIdx == TrainConfinement.NO_CARRIAGE) return;
        int dir = Integer.signum(targetIdx - echoIdx);
        if (dir != 0) {
            try {
                TrainConfinement.setMarchDirection(echo, dir);           // close in along the train
            } catch (Throwable ignored) {
                // best-effort; PlayerMob's own attack goal still functions once targeted
            }
        }
        if (Math.abs(echoIdx - targetIdx) <= 1) {
            echo.setTarget(target);                                       // engage even an invulnerable target
        }
    }
}
