package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ClientPortalRoomDepth;
import games.brennan.dungeontrain.client.CoordinateDisguise;
import games.brennan.dungeontrain.client.DebugScreenDepthLines;
import games.brennan.dungeontrain.client.DebugScreenDimensionLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
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
 * shift, {@link CoordinateDisguise} the answer this and every approved map/HUD mod share;
 * {@link DebugScreenDepthLines} does the rewriting, and says there why the printed text is
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
        dungeontrain$nameBand(cir);
        dungeontrain$disguise(cir);
    }

    @Inject(method = "getSystemInformation", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$disguiseRight(CallbackInfoReturnable<List<String>> cir) {
        dungeontrain$moveCarriageTargets(cir);
        dungeontrain$disguise(cir);
    }

    /**
     * A Targeted Block or Fluid on a train carriage prints Sable's plot position, some 20.48 million
     * blocks out; this brings it in by {@code MapReadoutText.PLOT_OFFSET}. Before the camera shift, so
     * that lands on the moved figure.
     */
    @Unique
    private static void dungeontrain$moveCarriageTargets(CallbackInfoReturnable<List<String>> cir) {
        try {
            List<String> lines = cir.getReturnValue();
            List<String> moved = DebugScreenDepthLines.carriageTargetsMoved(lines, CoordinateDisguise::onCarriage);
            if (moved != lines) cir.setReturnValue(moved);
        } catch (Throwable ignored) {
            // A readout: vanilla's own line stands if anything is off.
        }
    }

    /**
     * Prints the band the player is in as the dimension, and a dimensional carriage as a dimension of
     * its own. Only ever renames the overworld: a real dimension is already telling the truth.
     */
    @Unique
    private static void dungeontrain$nameBand(CallbackInfoReturnable<List<String>> cir) {
        try {
            List<String> lines = cir.getReturnValue();
            if (lines == null || lines.isEmpty()) return;

            String shown = CoordinateDisguise.shownDimensionId();
            ClientLevel level = Minecraft.getInstance().level;
            if (shown == null || level == null) return;
            cir.setReturnValue(DebugScreenDimensionLine.renamed(
                lines, level.dimension().location().toString(), shown));
        } catch (Throwable ignored) {
            // The same bargain as the coordinates below: vanilla's own line stands if anything is off.
        }
    }

    @Unique
    private static void dungeontrain$disguise(CallbackInfoReturnable<List<String>> cir) {
        try {
            List<String> lines = cir.getReturnValue();
            if (lines == null || lines.isEmpty()) return;

            Entity camera = Minecraft.getInstance().getCameraEntity();
            if (camera == null) return;

            DebugScreenDepthLines.Shift shift = CoordinateDisguise.shiftFor(camera);
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
