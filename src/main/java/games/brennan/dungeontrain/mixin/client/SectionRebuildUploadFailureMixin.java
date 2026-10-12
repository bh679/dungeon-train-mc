package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.gl.SectionUploadFailures;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Survives a section whose GPU upload failed, instead of crashing the client on the next frame.
 *
 * <p>When an upload throws, vanilla's rebuild completion hands the error to
 * {@code Minecraft.delayCrash("Rendering section")}, which {@code Minecraft.run} raises one loop later.
 * If the error came out of {@code VertexBuffer.upload} (recorded by
 * {@link VertexBufferUnuploadedDrawGuardMixin}) and {@link SectionUploadFailures} still has budget,
 * the crash is skipped and the section is marked dirty so the next frame rebuilds and re-uploads it.
 * Until then it keeps its previous mesh, or draws nothing if it never had one (the draw guard).
 * A failure that keeps recurring exhausts the budget and crashes as vanilla would, with the real
 * error in the report. Seen 2026-10-02 in the Far Lands on a 512 MB AMD iGPU.</p>
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$RebuildTask")
public abstract class SectionRebuildUploadFailureMixin {

    @Unique
    private static final Logger dungeontrain$LOGGER = LogUtils.getLogger();

    @Shadow
    @Final
    SectionRenderDispatcher.RenderSection this$1;

    @WrapOperation(
        method = "lambda$doTask$1",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;delayCrash(Lnet/minecraft/CrashReport;)V"))
    private void dungeontrain$forgiveFailedUpload(Minecraft minecraft, CrashReport report, Operation<Void> original) {
        Throwable error = report.getException();
        SectionUploadFailures failures = SectionUploadFailures.shared();
        if (!failures.isUploadFailure(error) || !failures.tryForgive(System.currentTimeMillis())) {
            original.call(minecraft, report);
            return;
        }
        this.this$1.setDirty(false);
        dungeontrain$LOGGER.warn("[DungeonTrain] Recovered from a failed section upload; rebuilding it ({}/{} this minute): {}",
            failures.forgivenInWindow(), SectionUploadFailures.MAX_FORGIVEN, error.toString());
    }
}
