package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ServerLevelAccessor;

/**
 * Loader-neutral form of NeoForge's {@code FinalizeSpawnEvent}: fired on the
 * server thread as a mob's spawn is finalized. NeoForge exposes a bespoke
 * {@code setSpawnCancelled(boolean)} result flag (NOT a propagation-stopping
 * cancel — every listener still fires and may set it). To carry that mutation
 * through loader-neutrally, the callback receives a {@code cancelSpawn} sink it
 * invokes to request cancellation — the bridge wires it to
 * {@code event.setSpawnCancelled(true)}. Both DT handlers were NORMAL priority and
 * both always run (no stop-on-cancel), so the bridge simply invokes every listener.
 *
 * @param entity       the mob being spawned (matches {@code getEntity()})
 * @param level        the spawn level accessor (matches {@code getLevel()})
 * @param spawnType    the spawn reason (matches {@code getSpawnType()})
 * @param x            the spawn X (matches {@code getX()})
 * @param cancelSpawn  run to cancel this spawn (maps to {@code setSpawnCancelled(true)})
 */
@FunctionalInterface
public interface DtFinalizeSpawnCallback {
    void onFinalizeSpawn(Mob entity, ServerLevelAccessor level, MobSpawnType spawnType,
                         double x, Runnable cancelSpawn);
}
