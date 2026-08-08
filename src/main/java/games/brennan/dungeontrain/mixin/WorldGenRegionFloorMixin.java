package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.WorldFloor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops world generation writing anything below the bedrock layer.
 *
 * <p>A Dungeon Train overworld's {@code dimension_type} runs deeper than its {@code noise_settings}
 * so the portal system has an empty basement to stamp its twin rooms into (see {@link WorldFloor}).
 * Vanilla's only floor test is {@code getMinBuildHeight()}, which is the <i>bottom of the basement</i>
 * — so a structure anchored at an absolute low Y ({@code trial_chambers} at −40..−20,
 * {@code ancient_city} at −27) generates happily under the bedrock row. Before the basement existed
 * those writes fell outside build height and were discarded; this restores that.</p>
 *
 * <p>Every worldgen block write in the game funnels through {@link WorldGenRegion#setBlock} — features,
 * carvers, surface rules and structure pieces alike — including the block entities it creates on the
 * way, so one guard here covers all of them. {@code addFreshEntity} covers structure-spawned mobs.</p>
 *
 * <p><b>A no-op wherever there is no basement.</b> In Compatible Terrain mode, the nether, the end and
 * any other mod's dimension the generator reaches the build floor, so {@code bedrockY} <i>is</i>
 * {@code getMinBuildHeight()} and this rejects nothing vanilla wouldn't. DT's own generation is safe
 * too: track and tunnels hang off {@code trainY}, and each {@code dungeon_train_y*} preset puts the
 * noise floor at that train Y. Portal twin rooms are stamped at runtime through {@code ServerLevel},
 * never through a {@code WorldGenRegion}, so the basement stays theirs.</p>
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionFloorMixin {

    @Shadow
    public abstract ServerLevel getLevel();

    /**
     * Resolved on first write and kept for the life of the region. A {@link WorldGenRegion} is built
     * per chunk step and used by one worldgen worker at a time, so this needs no synchronisation —
     * and the worst a stale read could cost is one redundant recompute of the same number.
     */
    @Unique
    private int dungeontrain$floorY = Integer.MIN_VALUE;

    @Unique
    private int dungeontrain$floorY() {
        if (dungeontrain$floorY == Integer.MIN_VALUE) {
            dungeontrain$floorY = WorldFloor.bedrockY(this.getLevel());
        }
        return dungeontrain$floorY;
    }

    @Inject(method = "setBlock", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$noBlocksBelowBedrock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (WorldFloor.isBelowFloor(pos.getY(), dungeontrain$floorY())) {
            cir.setReturnValue(false); // same answer vanilla gives for a write outside the world
        }
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$noEntitiesBelowBedrock(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (WorldFloor.isBelowFloor(entity.getBlockY(), dungeontrain$floorY())) {
            cir.setReturnValue(false);
        }
    }
}
