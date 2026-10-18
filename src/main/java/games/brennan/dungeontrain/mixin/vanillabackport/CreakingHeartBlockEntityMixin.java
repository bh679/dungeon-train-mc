package games.brennan.dungeontrain.mixin.vanillabackport;

import com.blackgear.vanillabackport.common.level.blockentities.CreakingHeartBlockEntity;
import com.blackgear.vanillabackport.common.level.entities.creaking.Creaking;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.compat.CreakingHeartPositions;
import games.brennan.dungeontrain.compat.CreakingHeartSpawnSpot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Lets a creaking heart work in a carriage: on the moving train and in a Test the Carriage copy.
 *
 * <p>Every distance VanillaBackport takes from the heart — the nearby-player check that wakes the
 * Creaking, the 34-block leash that tears it down, the redstone signal, the hurt sound and particle
 * trail — is measured from the heart's world-space position via {@link CreakingHeartPositions}, so a
 * plot-space heart on a Sable carriage sees the players riding it. Its {@code doMobSpawning} gate
 * also passes inside a running test copy. Lookups of the heart itself by position are untouched:
 * the plot is in the same level, so {@code getBlockEntity(home)} still finds it.</p>
 */
@Mixin(value = CreakingHeartBlockEntity.class, remap = false)
public abstract class CreakingHeartBlockEntityMixin {

    @Shadow
    protected abstract Optional<Creaking> getCreakingProtector();

    @WrapOperation(method = "serverTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
    private static boolean dungeontrain$testCopySpawns(GameRules rules, GameRules.Key<GameRules.BooleanValue> key,
                                                       Operation<Boolean> original,
                                                       @Local(argsOnly = true) Level level,
                                                       @Local(argsOnly = true) BlockPos pos) {
        return CreakingHeartPositions.spawningAllowed(level, pos, original.call(rules, key));
    }

    @WrapOperation(method = "serverTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getNearestPlayer(DDDDZ)Lnet/minecraft/world/entity/player/Player;"))
    private static Player dungeontrain$nearestPlayerInWorld(Level level, double x, double y, double z,
                                                            double distance, boolean creative,
                                                            Operation<Player> original,
                                                            @Local(argsOnly = true) BlockPos pos) {
        Vec3 world = CreakingHeartPositions.worldPoint(level, pos, 0, 0, 0);
        return original.call(level, world.x, world.y, world.z, distance, creative);
    }

    @WrapOperation(method = "serverTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;atCenterOf(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;"))
    private static Vec3 dungeontrain$hurtSoundFromWorld(Vec3i vec, Operation<Vec3> original,
                                                        @Local(argsOnly = true) Level level,
                                                        @Local(argsOnly = true) BlockPos pos) {
        return CreakingHeartPositions.worldCenter(level, pos);
    }

    @WrapOperation(method = "emitParticles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;atLowerCornerOf(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 dungeontrain$particleTrailFromWorld(Vec3i vec, Operation<Vec3> original) {
        BlockEntity self = (BlockEntity) (Object) this;
        return CreakingHeartPositions.worldPoint(self.getLevel(), self.getBlockPos(), 0, 0, 0);
    }

    /** Spawn in the heart's own space, not on the carriage roof — see {@link CreakingHeartSpawnSpot}. */
    @WrapOperation(method = "spawnProtector", at = @At(value = "INVOKE",
            target = "Lcom/blackgear/vanillabackport/core/util/SpawnExtras;trySpawnMob(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;IIILnet/minecraft/util/SpawnUtil$Strategy;Z)Ljava/util/Optional;"))
    private static <T extends net.minecraft.world.entity.Mob> Optional<T> dungeontrain$spawnInside(
            net.minecraft.world.entity.EntityType<T> type, net.minecraft.world.entity.MobSpawnType spawnType,
            net.minecraft.server.level.ServerLevel level, BlockPos pos, int attempts, int spread, int yOffset,
            net.minecraft.util.SpawnUtil.Strategy strategy, boolean checkCollisions,
            Operation<Optional<T>> original) {
        return CreakingHeartSpawnSpot.trySpawn(type, spawnType, level, pos);
    }

    @Inject(method = "distanceToCreaking", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$leashInWorld(CallbackInfoReturnable<Double> cir) {
        BlockEntity self = (BlockEntity) (Object) this;
        Vec3 home = CreakingHeartPositions.worldPoint(self.getLevel(), self.getBlockPos(), 0.5, 0, 0.5);
        cir.setReturnValue(getCreakingProtector()
                .map(creaking -> Math.sqrt(creaking.distanceToSqr(home)))
                .orElse(0.0));
    }
}
