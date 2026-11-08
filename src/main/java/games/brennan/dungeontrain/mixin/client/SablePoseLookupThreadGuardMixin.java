package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps chunk-mesh worker threads off Sable's client pose-supplier stack.
 *
 * <p>Sable's {@code clip_overwrite.ClientLevelMixin} holds its pose functions in a plain
 * {@code ObjectArrayList}: the render thread pushes and pops it every frame ({@code CameraMixin},
 * {@code GameRendererMixin}), and {@code sable$getPose} reads {@code getLast()}. Vanilla's section
 * rebuild runs on worker threads and reaches that read through
 * {@code RenderSection.getDistToPlayerSqr → ActiveSableCompanion.distanceSquaredWithSubLevels →
 * projectOutOfSubLevel}. A pop landing between the worker's size read and its element read hands
 * back the just-nulled slot, and the client crashes with "Batching sections" /
 * {@code NullPointerException ... ObjectList.getLast() is null} (player report, 2026-11-08). A moving
 * train rebuilds sections constantly, so every run rolls those dice.</p>
 *
 * <p>Off the render thread the call is answered with {@link SubLevel#logicalPose()} — the stack's
 * bottom entry, and what Sable itself uses for a level without the extension. A rebuild-order
 * distance has no use for the frame-interpolated render pose. On the render thread nothing changes.</p>
 *
 * <p>{@code remap = false}: Sable's own names. Bytecode-verified against {@code sable-2.0.5+mc1.21.1}
 * — both {@code projectOutOfSubLevel(Level, Vector3dc, Vector3d)} and
 * {@code projectOutOfSubLevel(Level, Position)} {@code INVOKEINTERFACE
 * LevelPoseProviderExtension.sable$getPose(SubLevel)}. <b>Re-verify on any {@code sable_version}
 * bump.</b></p>
 */
@Mixin(value = ActiveSableCompanion.class, remap = false)
public abstract class SablePoseLookupThreadGuardMixin {

    @WrapOperation(
            method = {
                    "projectOutOfSubLevel(Lnet/minecraft/world/level/Level;Lorg/joml/Vector3dc;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;",
                    "projectOutOfSubLevel(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Position;)Lnet/minecraft/world/phys/Vec3;"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/mixinterface/clip_overwrite/LevelPoseProviderExtension;"
                            + "sable$getPose(Ldev/ryanhcode/sable/sublevel/SubLevel;)"
                            + "Ldev/ryanhcode/sable/companion/math/Pose3dc;"),
            remap = false,
            require = 2)
    private Pose3dc dungeontrain$logicalPoseOffRenderThread(
            final LevelPoseProviderExtension extension, final SubLevel subLevel,
            final Operation<Pose3dc> original) {
        if (RenderSystem.isOnRenderThread()) {
            return original.call(extension, subLevel);
        }
        return subLevel.logicalPose();
    }
}
