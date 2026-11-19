package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.ship.PlotWorldPositions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.trialspawner.PlayerDetector;
import net.minecraft.world.level.block.entity.vault.VaultSharedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * A vault only takes a key while a player stands near it, and vanilla measures "near" from the
 * vault's block position. On the train that position is in the carriage's Sable plot, far from every
 * player, so the vault never woke and silently ignored keys. Detect players around the carriage's
 * current world position of the vault instead; a vault off the train keeps its own position.
 */
@Mixin(VaultSharedData.class)
public abstract class VaultSharedDataMixin {

    @ModifyArg(
        method = "updateConnectedPlayersWithinRange",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/trialspawner/PlayerDetector;detect(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/block/entity/trialspawner/PlayerDetector$EntitySelector;Lnet/minecraft/core/BlockPos;DZ)Ljava/util/List;"),
        index = 2)
    private BlockPos dungeontrain$detectFromWorldPos(ServerLevel level, PlayerDetector.EntitySelector selector,
                                                     BlockPos pos, double range, boolean requireLineOfSight) {
        return PlotWorldPositions.worldBlock(level, pos);
    }
}
