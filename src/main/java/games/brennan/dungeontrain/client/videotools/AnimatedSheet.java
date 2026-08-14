package games.brennan.dungeontrain.client.videotools;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws one frame of a {@link VideoTool}'s grid sprite sheet, scaled into a target rect.
 *
 * <p>Minecraft has no animated-image widget for GUI art — an animated tile is a sheet of frames
 * you blit one cell at a time. The cell is picked from the <b>wall clock</b>
 * ({@link Util#getMillis()}) rather than a tick counter, the same reason
 * {@code DiscordIconButton}'s pulse does: these play on the title-screen menus, where the game
 * clock is not running.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class AnimatedSheet {

    /** Playback rate of the tiles — matches the {@code FPS} the build script resampled to. */
    private static final int FPS = 12;
    private static final long MS_PER_FRAME = 1000L / FPS;

    private AnimatedSheet() {}

    /** Blit the current frame of {@code tool} into the rect at ({@code x}, {@code y}). */
    public static void draw(GuiGraphics g, VideoTool tool, int x, int y, int width, int height) {
        int frame = (int) ((Util.getMillis() / MS_PER_FRAME) % tool.frames());
        int col = frame % tool.cols();
        int row = frame / tool.cols();

        g.blit(tool.sheet(), x, y, width, height,
                (float) (col * VideoTool.FRAME_W), (float) (row * VideoTool.FRAME_H),
                VideoTool.FRAME_W, VideoTool.FRAME_H,
                tool.cols() * VideoTool.FRAME_W, tool.rows() * VideoTool.FRAME_H);
    }
}
