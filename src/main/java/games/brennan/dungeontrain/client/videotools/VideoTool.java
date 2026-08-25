package games.brennan.dungeontrain.client.videotools;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * One filming command as it appears on the Video Tools page: a looping clip of the command in
 * action, the command itself, a one-line blurb for the tile, and the body copy for its detail
 * page. The two constants here are the single source of truth both {@link VideoToolsScreen}
 * (the tile hub) and {@link VideoToolDetailScreen} read.
 *
 * <p>{@code cols}/{@code rows}/{@code frames} describe the sprite sheet built by
 * {@code scripts/videotools/build-tiles.sh} — <b>they must match what that script printed</b>.
 * The grid is filled left-to-right, top-to-bottom, and the trailing cells of the last row are
 * blank whenever {@code frames < cols * rows}, which is why the frame count is carried
 * explicitly rather than inferred as {@code cols * rows}.</p>
 *
 * @param id        lang-key segment, also the texture basename
 * @param command   the command as the player types it
 * @param frames    how many cells of the sheet are real frames
 * @param cols      sheet grid columns
 * @param rows      sheet grid rows
 * @param bodyKeys  detail-page paragraphs, in order, below the command
 */
public record VideoTool(String id, String command, int frames, int cols, int rows, List<String> bodyKeys) {

    /** Per-frame size in the sheet — the {@code TILE_W}/{@code TILE_H} the build script uses. */
    public static final int FRAME_W = 160;
    public static final int FRAME_H = 100;

    public static final VideoTool CINEMATOGRAPHER = new VideoTool(
            "cinematographer", "/dt cinematographer", 41, 7, 6,
            List.of("desc", "distance", "clearview", "free_play"));

    public static final VideoTool CINEMATIC = new VideoTool(
            "cinematic", "/dt cinematic", 11, 4, 3,
            List.of("desc", "hotkey"));

    /** Both tools, in the order they appear on the hub. */
    public static final List<VideoTool> ALL = List.of(CINEMATOGRAPHER, CINEMATIC);

    public ResourceLocation sheet() {
        return ResourceLocation.fromNamespaceAndPath("dungeontrain", "textures/gui/videotools/" + id + ".png");
    }

    /** "Cinematographer mode" — the tile caption and the detail page's title. */
    public MutableComponent header() {
        return tr("header");
    }

    /** The one-liner under the command on the tile. */
    public MutableComponent blurb() {
        return tr("blurb");
    }

    public MutableComponent body(String key) {
        return tr(key);
    }

    private MutableComponent tr(String suffix) {
        return Component.translatable("gui.dungeontrain.video_tools." + id + "." + suffix);
    }
}
