package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips drawing a {@link VertexBuffer} that never received an upload, so a failed section upload is
 * reported as itself rather than as {@code Cannot read field "asGLMode" because "this.mode" is null}.
 *
 * <p>{@code mode} is set only at the end of a successful {@code upload(MeshData)}. When a section's
 * upload throws, vanilla's {@code RebuildTask} hands the error to {@code Minecraft.delayCrash} and then
 * <i>still</i> stores the compiled section with that layer marked non-empty. The upload runs in
 * {@code compileSections}, earlier in the same {@code renderLevel} that goes on to
 * {@code renderSectionLayer}, so the very next draw of that buffer NPEs and its crash report replaces
 * the delayed one — the real error is never shown. Reported 2026-10-02 in the Far Lands on a 512 MB
 * AMD iGPU, whose dense sections are the likeliest to fail a GPU allocation mid-upload.</p>
 *
 * <p>This does not prevent the crash: {@code Minecraft.run} raises the delayed crash on its next loop,
 * so the game still stops — but with a "Rendering section" report naming the real upload error
 * (verified 2026-10-03 with a forced upload failure). Any other never-uploaded buffer is simply left
 * undrawn. The upload itself is wrapped only to log what failed, then rethrows, so vanilla's handling
 * is unchanged. Remove once vanilla or NeoForge stop storing a section whose upload failed.</p>
 */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferUnuploadedDrawGuardMixin {

    @Unique
    private static final Logger dungeontrain$LOGGER = LogUtils.getLogger();

    @Unique
    private static final long dungeontrain$LOG_INTERVAL_MS = 10_000L;

    @Unique
    private static volatile long dungeontrain$lastDrawLogMs;

    @Unique
    private static volatile long dungeontrain$lastUploadLogMs;

    @Shadow
    @Nullable
    private VertexFormat.Mode mode;

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skipUnuploadedDraw(CallbackInfo ci) {
        if (this.mode != null) return;
        ci.cancel();
        long now = System.currentTimeMillis();
        if (now - dungeontrain$lastDrawLogMs >= dungeontrain$LOG_INTERVAL_MS) {
            dungeontrain$lastDrawLogMs = now;
            dungeontrain$LOGGER.warn("[DungeonTrain] Skipped drawing a vertex buffer that was never uploaded (its upload failed)");
        }
    }

    @Inject(method = "drawWithShader", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skipUnuploadedDrawWithShader(CallbackInfo ci) {
        // _drawWithShader hands mode to ShaderInstance.setDefaultUniforms before it reaches draw().
        if (this.mode == null) ci.cancel();
    }

    @WrapMethod(method = "upload")
    private void dungeontrain$logFailedUpload(MeshData meshData, Operation<Void> original) {
        try {
            original.call(meshData);
        } catch (RuntimeException | Error e) {
            long now = System.currentTimeMillis();
            if (now - dungeontrain$lastUploadLogMs >= dungeontrain$LOG_INTERVAL_MS) {
                dungeontrain$lastUploadLogMs = now;
                MeshData.DrawState state = meshData.drawState();
                dungeontrain$LOGGER.error("[DungeonTrain] Vertex buffer upload failed (mode={}, vertices={}, indices={})",
                    state.mode(), state.vertexCount(), state.indexCount(), e);
            }
            throw e;
        }
    }
}
