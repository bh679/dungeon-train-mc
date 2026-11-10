package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.ship.sable.RapierNativePath;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps a locked Rapier natives file from crashing world load.
 *
 * <p>{@code Rapier3D.loadLibrary} (its static initialiser, first reached from
 * {@code RapierPhysicsPipeline.init}) resolves {@code .sable/natives/<native>}, deletes it if it
 * exists, then re-extracts and {@code System.load}s it. On Windows the delete throws
 * {@code AccessDeniedException} while another process still has the DLL loaded, and Sable reports
 * it as a "Sable linking with Rapier natives" crash. Seen 2026-11-10 (Windows 10, Modrinth App,
 * DT 0.1750.0): {@code Files.delete ← Rapier3D.loadLibrary:101 ← RapierPhysicsPipeline.init ←
 * Sable.defaultSubLevelContainerInitializer}. Sable issues #1631, #1533, #743 look the same.</p>
 *
 * <p>We swap the resolved path for {@link RapierNativePath#choose}: when the file can be deleted
 * nothing changes; when it can't, Sable extracts to a per-process sibling and loads that. Sable's
 * own exists/delete/create/copy/load steps run unchanged on whichever path we return.</p>
 *
 * <p>String target + {@code remap = false}: {@code Rapier3D} ships in Sable's jar-in-jar
 * ({@code sable_rapier}), as with {@link RapierPipelineNoSyncLoadMixin}. Bytecode-verified against
 * {@code sable_rapier-1.21.1-2.0.5}: {@code loadLibrary} has exactly one
 * {@code Path.resolve(String)} call, {@code NATIVE_DIR.resolve(NATIVE_NAME)}.
 * <b>Re-verify on any {@code sable_version} bump.</b></p>
 */
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.Rapier3D", remap = false)
public abstract class RapierNativeLockedDllMixin {

    @ModifyExpressionValue(
            method = "loadLibrary",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/nio/file/Path;resolve(Ljava/lang/String;)Ljava/nio/file/Path;"
            )
    )
    private static Path dungeontrain$avoidLockedNatives(final Path canonical) {
        return RapierNativePath.choose(canonical);
    }
}
