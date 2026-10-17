package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ClientPortalRoomDepth;
import games.brennan.dungeontrain.client.DebugScreenDepthLines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Makes the debug screen report a surface Y, and the room's centre as X 0, Z 0, while the camera is
 * inside a dimensional carriage.
 *
 * <p>A dimensional carriage is a twin corridor stamped into the sealed space under the world (or,
 * inside the upside-down band, over its lid). Left alone, F3 prints a Y a hundred and fifty blocks
 * down and gives the whole trick away, and its X and Z place the room somewhere along the track
 * when it is meant to be a space of its own. {@link ClientPortalRoomDepth} holds the box and the
 * shift; {@link DebugScreenDepthLines} does the rewriting, and says there why the printed text is
 * what is touched rather than the position the method reads.</p>
 *
 * <p>Both columns: the left carries the camera's position, the right the Targeted Block and Fluid
 * lines. At {@code RETURN}, so vanilla has already done every lookup it makes from the real
 * position (biome, light, chunk) against coordinates that are still true.</p>
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayDepthMixin {

    @Inject(method = "getGameInformation", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$disguiseLeft(CallbackInfoReturnable<List<String>> cir) {
        dungeontrain$disguise(cir);
    }

    @Inject(method = "getSystemInformation", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$disguiseRight(CallbackInfoReturnable<List<String>> cir) {
        dungeontrain$disguise(cir);
    }

    @Unique
    private static void dungeontrain$disguise(CallbackInfoReturnable<List<String>> cir) {
        try {
            List<String> lines = cir.getReturnValue();
            if (lines == null || lines.isEmpty()) return;

            Entity camera = Minecraft.getInstance().getCameraEntity();
            if (camera == null) return;

            DebugScreenDepthLines.Shift shift =
                ClientPortalRoomDepth.shiftAt(camera.getX(), camera.getY(), camera.getZ());
            if (shift.isZero()) return;

            BlockPos block = camera.blockPosition();
            cir.setReturnValue(DebugScreenDepthLines.shifted(
                lines, block.getX(), block.getY(), block.getZ(), shift));
        } catch (Throwable ignored) {
            // The debug screen is a readout. Anything unexpected here leaves vanilla's own lines
            // standing rather than taking the overlay, and with it the frame, down with it.
        }
    }
}
