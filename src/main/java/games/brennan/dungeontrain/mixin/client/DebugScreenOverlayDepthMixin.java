package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ClientBandDimension;
import games.brennan.dungeontrain.client.ClientPortalRoomDepth;
import games.brennan.dungeontrain.client.ClientVoidBand;
import games.brennan.dungeontrain.client.DebugScreenDepthLines;
import games.brennan.dungeontrain.client.DebugScreenDimensionLine;
import games.brennan.dungeontrain.client.FarLandsDebugX;
import games.brennan.dungeontrain.worldgen.BandDimensionName;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
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
        dungeontrain$nameBand(cir);
        dungeontrain$disguise(cir);
    }

    @Inject(method = "getSystemInformation", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$disguiseRight(CallbackInfoReturnable<List<String>> cir) {
        dungeontrain$disguise(cir);
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

            Minecraft minecraft = Minecraft.getInstance();
            ClientLevel level = minecraft.level;
            Entity camera = minecraft.getCameraEntity();
            if (level == null || camera == null || !level.dimension().equals(Level.OVERWORLD)) return;

            boolean inCarriage =
                !ClientPortalRoomDepth.shiftAt(camera.getX(), camera.getY(), camera.getZ()).isZero();
            String shown = inCarriage ? BandDimensionName.DIMENSIONAL_CARRIAGE : ClientBandDimension.id();
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

            DebugScreenDepthLines.Shift shift =
                ClientPortalRoomDepth.shiftAt(camera.getX(), camera.getY(), camera.getZ());
            if (shift.isZero()) shift = dungeontrain$farLandsShift(camera);
            if (shift.isZero()) return;

            BlockPos block = camera.blockPosition();
            cir.setReturnValue(DebugScreenDepthLines.shifted(
                lines, block.getX(), block.getY(), block.getZ(), shift));
        } catch (Throwable ignored) {
            // The debug screen is a readout. Anything unexpected here leaves vanilla's own lines
            // standing rather than taking the overlay, and with it the frame, down with it.
        }
    }

    /**
     * The X the legacy run around the Far Lands prints ({@link FarLandsDebugX}). Read from the client's own
     * cycle, as the clocks and compasses read theirs ({@code OtherworldBand}), so it needs no packet.
     */
    @Unique
    private static DebugScreenDepthLines.Shift dungeontrain$farLandsShift(Entity camera) {
        if (!camera.level().dimension().equals(Level.OVERWORLD) || !ClientVoidBand.startsWithTrain()) {
            return DebugScreenDepthLines.Shift.NONE;
        }
        long dx = FarLandsDebugX.offsetAt(WorldGenCycle.fromConfig(), camera.getBlockX());
        return dx == 0L ? DebugScreenDepthLines.Shift.NONE : new DebugScreenDepthLines.Shift(dx, 0, 0);
    }
}
