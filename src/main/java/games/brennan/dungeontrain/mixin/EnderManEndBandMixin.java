package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.event.EndBandSpawnRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * An enderman in a sampled End-band column ({@link EndBandSpawnRules#isSampledColumn}) ignores daylight, as in
 * the real End, which has no time of day. Vanilla reads it twice after the spawn: under open sky by day an
 * enderman drops its target and teleports, and in bright light any monster despawns faster. The vanilla End
 * band keeps both.
 */
@Mixin(EnderMan.class)
public abstract class EnderManEndBandMixin extends Monster {

    private EnderManEndBandMixin(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    /** Asked last before the roll, so the column is read only by an enderman standing in full daylight. */
    @Redirect(method = "customServerAiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;canSeeSky(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean dungeontrain$noSunTeleportInEndBand(Level level, BlockPos pos) {
        return level.canSeeSky(pos)
                && !(level instanceof ServerLevel server && EndBandSpawnRules.isSampledColumn(server, pos.getX(), pos.getZ()));
    }

    /** Monster's own check first: the column is read only when bright light would otherwise count. */
    @Override
    protected void updateNoActionTime() {
        if (getLightLevelDependentMagicValue() > 0.5F && level() instanceof ServerLevel server
                && EndBandSpawnRules.isSampledColumn(server, blockPosition().getX(), blockPosition().getZ())) {
            return;
        }
        super.updateNoActionTime();
    }
}
