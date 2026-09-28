package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.GravityTickSuppression;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.WorldGenTickAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops worldgen-time scheduled ticks for gravity blocks ({@link Fallable}) while
 * {@link GravityTickSuppression} is held — so a stacks-band tower's sand, gravel, concrete powder, anvils
 * and suspicious blocks hang over the void like vanilla cave-ceiling sand instead of falling the moment
 * the chunk loads. See {@link GravityTickSuppression} for where the tick comes from.
 *
 * <p>{@link WorldGenTickAccess} is the tick sink every {@code WorldGenRegion} hands out (block and fluid
 * alike); ticks scheduled through it are persisted in the {@code ProtoChunk}. Cancel-only at {@code HEAD},
 * so it composes with any other hook. The thread-local test comes first: off the guarded stamp the cost is
 * one {@code ThreadLocal} read. Fluid ticks never match ({@code Fluid} is not {@code Fallable}).</p>
 */
@Mixin(WorldGenTickAccess.class)
public class WorldGenTickAccessGravityMixin<T> {

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$dropGravityTickWhileSuppressed(ScheduledTick<T> tick, CallbackInfo ci) {
        if (!GravityTickSuppression.isActive()) return;
        if (tick.type() instanceof Fallable) {
            ci.cancel(); // the block stays put until a real in-game neighbour update re-schedules it
        }
    }
}
