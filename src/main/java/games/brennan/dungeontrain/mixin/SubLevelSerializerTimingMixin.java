package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import games.brennan.dungeontrain.ship.sable.SableStorageSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times Sable's live-sub-level-to-NBT serialisation so the {@code [DT-SableSave]} line can say
 * how much of a save was serialising rather than writing or syncing. {@code saveAll} re-serialises
 * every loaded sub-level on every save, and the cull path ({@code processUnload},
 * {@code moveToUnloaded}) serialises inside the save too, so this timer covers the whole hitch.
 *
 * <p>Bytecode-verified against {@code sable-2.0.5+mc1.21.1}: {@code toData} is the single
 * {@code public static SubLevelData toData(ServerSubLevel, List<UUID>)}. <b>Re-verify on any
 * {@code sable_version} bump.</b> {@code remap = false}: Sable's own names.</p>
 */
@Mixin(value = SubLevelSerializer.class, remap = false)
public abstract class SubLevelSerializerTimingMixin {

    @Unique
    private static final ThreadLocal<Long> DUNGEONTRAIN$START = ThreadLocal.withInitial(() -> 0L);

    @Inject(method = "toData", at = @At("HEAD"))
    private static void dungeontrain$startSerialise(CallbackInfoReturnable<?> cir) {
        DUNGEONTRAIN$START.set(System.nanoTime());
    }

    @Inject(method = "toData", at = @At("RETURN"))
    private static void dungeontrain$endSerialise(CallbackInfoReturnable<?> cir) {
        SableStorageSync.addSerialiseNanos(System.nanoTime() - DUNGEONTRAIN$START.get());
    }
}
