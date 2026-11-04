package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.EditorPlotPreviewPacket;
import games.brennan.dungeontrain.net.StagePreviewPacket;
import games.brennan.dungeontrain.template.TemplateDecor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The plot a player stands in, captured from the world for the editor screen's model — see
 * {@link games.brennan.dungeontrain.net.EditorPlotPreviewRequestPacket}.
 *
 * <p>The plot is found by {@link EditorPlotTransformer#resolve}, so it is exactly the region the
 * offset buttons just moved.</p>
 */
public final class EditorPlotPreviewCapture {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Permission the editor commands require. */
    private static final int PERMISSION_LEVEL = 2;

    private EditorPlotPreviewCapture() {}

    /** The reply for {@code player} — always an answer, "nothing" included. */
    public static EditorPlotPreviewPacket compose(ServerPlayer player) {
        if (!player.hasPermissions(PERMISSION_LEVEL)) return EditorPlotPreviewPacket.NONE;
        ServerLevel level = player.getServer().overworld();
        if (!EditorWorldLayout.isEditorWorld(level)) return EditorPlotPreviewPacket.NONE;
        EditorPlotTransformer.Region region = EditorPlotTransformer.resolve(player, level).orElse(null);
        if (region == null) return EditorPlotPreviewPacket.NONE;
        try {
            CompoundTag tag = TemplateDecor.capture(level, region.origin(), region.size(), Blocks.AIR)
                .save(new CompoundTag());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(out));   // uncompressed, as BuilderRelayPreview.decode reads
            byte[] bytes = out.toByteArray();
            return bytes.length > StagePreviewPacket.MAX_BYTES
                ? EditorPlotPreviewPacket.NONE : new EditorPlotPreviewPacket(true, bytes);
        } catch (RuntimeException | IOException e) {
            LOGGER.warn("[DungeonTrain] Live plot preview failed for {}: {}", region.name(), e.toString());
            return EditorPlotPreviewPacket.NONE;
        }
    }
}
