package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.NoteKind;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Steers live note echoes toward their target each tick: it marches the echo along the train toward
 * the target's carriage so the two actually meet. What happens on arrival depends on the
 * {@link NoteKind}:
 * <ul>
 *   <li>{@link NoteKind#DEATH} — once in the same/adjacent carriage the target is <b>forced</b>, so
 *       the echo engages even an invulnerable (Creative/Spectator) player: vanilla target-selection
 *       skips those, but the curse should not. Flee-by-trait is preserved:
 *       {@code FleeFromCategoryGoal} (higher priority) is reaction-driven, not {@code getTarget}-driven,
 *       so a fleeing echo still flees despite a forced target.</li>
 *   <li>{@link NoteKind#LOVE} — the march is the whole point (it is walking the train to find them)
 *       and no target is ever forced. Forcing one would make it attack the very player it was sent
 *       to love, overriding the feeling the spawner seeded.</li>
 * </ul>
 *
 * <p>Echoes are tracked by UUID (registered at spawn) rather than a spatial scan, because a
 * carriage-bound echo lives in Sable shipyard coordinates far from the player's world position — a
 * world-space AABB around the player would never find it. Steering compares carriage indices
 * ({@link TrainConfinement#carriageIndex}), a frame the echo and the player share.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DeathNoteEchoController {

    private static final int SCAN_PERIOD_TICKS = 10;

    /** One tracked echo: who it was sent to, and which kind of note sent it. */
    private record Steered(UUID targetUuid, NoteKind kind) {}

    /** echo entity UUID → its steering entry. Registered at spawn, cleared on echo death. */
    private static final Map<UUID, Steered> ACTIVE = new ConcurrentHashMap<>();

    private DeathNoteEchoController() {}

    /** Called by {@code DeathNoteEchoSpawner} once the echo is added to the world. */
    public static void register(UUID echoUuid, UUID targetUuid, NoteKind kind) {
        if (echoUuid != null && targetUuid != null) {
            ACTIVE.put(echoUuid, new Steered(targetUuid, kind == null ? NoteKind.DEATH : kind));
        }
    }

    /** Called by {@code DeathNoteEvents} when a note echo dies. */
    public static void unregister(UUID echoUuid) {
        if (echoUuid != null) ACTIVE.remove(echoUuid);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (ACTIVE.isEmpty()) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;
        for (Map.Entry<UUID, Steered> e : ACTIVE.entrySet()) {
            if (!(level.getEntity(e.getKey()) instanceof PlayerMobEntity echo)) continue; // other level / unloaded
            if (!echo.isAlive()) { ACTIVE.remove(e.getKey()); continue; }
            ServerPlayer target = level.getServer().getPlayerList().getPlayer(e.getValue().targetUuid());
            if (target == null) continue;                                    // target offline
            steer(echo, target, e.getValue().kind());
        }
    }

    /**
     * March the echo toward the target's carriage and, for a curse only, force the target once
     * alongside. Uses carriage indices (a frame the echo and the player share), NOT raw world coords
     * — the echo is in shipyard space and the player in world space, so subtracting {@code getX()}
     * would mix frames.
     */
    private static void steer(PlayerMobEntity echo, ServerPlayer target, NoteKind kind) {
        int echoIdx = TrainConfinement.carriageIndex(echo);
        int targetIdx = TrainConfinement.carriageIndex(target);
        if (echoIdx == TrainConfinement.NO_CARRIAGE || targetIdx == TrainConfinement.NO_CARRIAGE) return;
        int dir = Integer.signum(targetIdx - echoIdx);
        if (dir != 0) {
            try {
                TrainConfinement.setMarchDirection(echo, dir);           // close the distance along the train
            } catch (Throwable ignored) {
                // best-effort; PlayerMob's own goals still function once the two are in the same cart
            }
        }
        // A Love Note echo is never given a target — it arrived to greet them, and PlayerMob's
        // social goals take it from here off the feeling the spawner seeded.
        if (kind != NoteKind.LOVE && Math.abs(echoIdx - targetIdx) <= 1) {
            echo.setTarget(target);                                       // engage even an invulnerable target
        }
    }
}
