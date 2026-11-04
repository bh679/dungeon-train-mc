package games.brennan.dungeontrain.client.tributeroom;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A screen on a TV: the texture it is drawn to, the TV's blocks and the way its picture faces — and
 * where the crosshair is on it, as GUI coordinates the screen's buttons understand. Shared by the
 * death visit's TV and any tribute TV replaying its owner's death.
 */
public record TvScreenBinding(ScreenToTexture texture, BoundingBox screen, Direction facing) {

    public boolean covers(BlockPos pos) {
        return screen.isInside(pos);
    }

    /** GUI coordinates of the crosshair on the picture, or {@link TvPointer#OFF_SCREEN} when it is not on this TV's face. */
    public int[] mouse() {
        int[] off = {TvPointer.OFF_SCREEN, TvPointer.OFF_SCREEN};
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.hitResult instanceof BlockHitResult hit)) return off;
        if (!screen.isInside(hit.getBlockPos()) || hit.getDirection() != facing) return off;
        double[] uv = TvPointer.faceFraction(facing, screen, hit.getLocation());
        if (uv == null) return off;
        double screenAspect = (facing.getAxis() == Direction.Axis.Z ? screen.getXSpan() : screen.getZSpan()) / (double) screen.getYSpan();
        return TvPointer.toGui(uv[0], uv[1], screenAspect, texture.aspect(), texture.guiWidth(), texture.guiHeight());
    }

    /** Whether the texture's screen is also the window's right now — then the window ticks it, not us. */
    public boolean zoomed() {
        return texture.screen() != null && Minecraft.getInstance().screen == texture.screen();
    }
}
