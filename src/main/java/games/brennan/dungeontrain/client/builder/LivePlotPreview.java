package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.relay.BuilderRelayPreview;
import games.brennan.dungeontrain.editor.TemplateCells;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorPlotPreviewPacket;
import games.brennan.dungeontrain.net.EditorPlotPreviewRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Map;

/**
 * The editor screen's model of the plot the author stands in, as its blocks are right now.
 *
 * <p>The model normally bakes the saved file, which an offset, undo or redo press leaves alone, so
 * after one of those the screen asks the server for the plot itself
 * ({@link EditorPlotPreviewRequestPacket}) and draws that until the screen closes. One plot at a
 * time — only the plot being stood in can be pressed on. Render thread only, like every mesh cache
 * here.</p>
 */
public final class LivePlotPreview {

    /** Which template the capture asked for is of; whatever the caller keys its selection by. */
    private static String askedFor = "";
    private static String key = "";
    /** Null for a plot captured empty, which is still a capture: it draws as nothing, not as the file. */
    private static BuilderTileMesh mesh;
    private static boolean captured;

    private LivePlotPreview() {}

    /** Ask for {@code templateKey}'s plot as it stands. Call after the command that moved it. */
    public static void request(String templateKey) {
        if (templateKey == null || templateKey.isEmpty()) return;
        askedFor = templateKey;
        DungeonTrainNet.sendToServer(new EditorPlotPreviewRequestPacket());
    }

    /** Whether there is a capture of {@code templateKey} to draw. */
    public static boolean has(String templateKey) {
        return captured && key.equals(templateKey);
    }

    /** Draw the capture of {@code templateKey}, or answer false when there is none. */
    public static boolean draw(GuiGraphics g, String templateKey, int x, int y, int w, int h, float yaw, float fill) {
        if (!has(templateKey)) return false;
        if (mesh != null) BuilderTileModelRenderer.render(g, mesh, x, y, w, h, yaw, fill);
        return true;
    }

    /**
     * An answer arrived: bake it in place of the last one. A "nothing" answer keeps the last
     * capture rather than dropping back to the file, which is older still.
     */
    public static void accept(EditorPlotPreviewPacket packet) {
        if (!packet.found() || askedFor.isEmpty()) return;
        CompoundTag tag = BuilderRelayPreview.decode(packet.template());
        Minecraft mc = Minecraft.getInstance();
        if (tag == null || tag.isEmpty() || mc.level == null) return;
        try {
            StructureTemplate template = new StructureTemplate();
            template.load(mc.level.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
            Map<BlockPos, BlockState> cells = TemplateCells.of(template);
            BuilderTileMesh baked = cells.isEmpty() ? null : BuilderTileMesh.bake(cells);
            clear();
            key = askedFor;
            mesh = baked;
            captured = true;
        } catch (RuntimeException e) {
            // A capture this version cannot read leaves the last picture up rather than the screen down.
        }
    }

    /** Drop the capture, closing its GPU buffer. On the way out of the screen. */
    public static void clear() {
        if (mesh != null) mesh.close();
        mesh = null;
        captured = false;
        key = "";
    }
}
