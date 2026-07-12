package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.train.TrainCarriageAppender;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Holds the whole train resident across a world save so the save can't cull (and unrecoverably
 * regenerate) the train's Sable sub-levels — the "train disappears on autosave" report.
 *
 * <p>The train is a Sable physics ship made of sub-levels. A save is a multi-hundred-ms hitch,
 * and around it a moving train slides on while the rider is momentarily left behind, which reads
 * as "rider not near" and would let Sable cull the train — and an un-serialized group culls into
 * a null-pointer holding entry {@code snatchAndLoad} can't revive, so it regenerates fresh. This
 * is the same failure mode {@code ResumeWatchdog} guards for a singleplayer pause/resume
 * (#547/#548); here we apply the identical whole-train hold proactively to the save.</p>
 *
 * <p>Injected at HEAD of {@code saveEverything(boolean, boolean, boolean)} — the method the tick
 * loop calls inline on the periodic autosave (1.21.1 has no dedicated {@code autoSave()} method),
 * and also the path for an explicit {@code /save-all} or save-on-close. Holding on those too is
 * harmless and self-draining, so the train is protected across <em>every</em> save. Holding before
 * the save also lets that save serialize the held carriages, so they become recoverable and
 * drainable afterward — a net positive. The hold self-drains back to the trailing-N window once
 * the rider re-anchors (the grace lapses).</p>
 *
 * <p>Common-side: {@code saveEverything} lives on {@link MinecraftServer}, so this covers both the
 * integrated and dedicated servers. Server-thread only by construction (saves run on the server
 * thread).</p>
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerSaveMixin {

    /**
     * Grace window (ticks ≈ 3 s) the held train stays pinned through the post-save hitch while
     * the flung-off rider re-anchors — parity with {@code ResumeWatchdog.RESUME_GRACE_TICKS}.
     */
    @Unique
    private static final int DUNGEONTRAIN$SAVE_HOLD_GRACE_TICKS = 60;

    @Inject(method = "saveEverything", at = @At("HEAD"))
    private void dungeontrain$holdTrainAcrossSave(
        boolean suppressLog, boolean flush, boolean forced, CallbackInfoReturnable<Boolean> cir) {
        TrainCarriageAppender.holdAllLoadedTrains(
            (MinecraftServer) (Object) this, DUNGEONTRAIN$SAVE_HOLD_GRACE_TICKS);
    }
}
