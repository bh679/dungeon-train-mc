package games.brennan.dungeontrain.fabric.mixin;

import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtFinalizeSpawnCallback;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gap-filler for {@code DtEvents.FINALIZE_SPAWN} (NeoForge {@code FinalizeSpawnEvent}).
 * Fabric has no finalize-spawn event; fires at the HEAD of {@code Mob.finalizeSpawn}.
 *
 * <p><b>Fabric-v1 divergence:</b> NeoForge's {@code setSpawnCancelled} prevents the mob
 * being added; on Fabric the cancel sink calls {@code discard()} (best-effort post-hoc
 * removal) since the mob is added by the caller after {@code finalizeSpawn} returns. The
 * (dominant) modify-the-mob use of both DT handlers is unaffected.</p>
 */
@Mixin(Mob.class)
public abstract class MobFinalizeSpawnMixin {

    @Inject(method = "finalizeSpawn", at = @At("HEAD"))
    private void dungeonTrain$finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                            MobSpawnType spawnType, SpawnGroupData spawnData,
                                            CallbackInfoReturnable<SpawnGroupData> cir) {
        if (DtEvents.FINALIZE_SPAWN.isEmpty()) {
            return;
        }
        Mob self = (Mob) (Object) this;
        Runnable cancelSpawn = self::discard;
        for (DtFinalizeSpawnCallback cb : DtEvents.FINALIZE_SPAWN.listeners()) {
            cb.onFinalizeSpawn(self, level, spawnType, self.getX(), cancelSpawn);
        }
    }
}
