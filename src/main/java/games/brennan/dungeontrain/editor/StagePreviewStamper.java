package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.StagePreviewPacket;
import games.brennan.dungeontrain.net.StagePreviewRequestPacket;
import games.brennan.dungeontrain.template.TemplateDecor;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * Composes the Stages tab's model on request: one carriage stamped with a stage's linked parts
 * and its block variants rolled from the asked seed, captured as structure NBT, and the scratch
 * footprint erased again — all within one server tick.
 *
 * <p>Stamped into a scratch corner of the editor world far from the plot rows, at plot height,
 * where the void preset generates the chunk for free. Nothing about the plots or the stage
 * selection changes: this is a picture, not an edit.</p>
 */
public final class StagePreviewStamper {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Permission the editor commands require. */
    private static final int PERMISSION_LEVEL = 2;

    /**
     * The scratch footprint's origin: the plot rows run out along {@code +Z} from zero, so a
     * carriage this far along {@code -Z} can never sit under one.
     */
    static final BlockPos SCRATCH_ORIGIN = new BlockPos(0, EditorLayout.PLOT_Y, -4096);

    private StagePreviewStamper() {}

    /** The reply to {@code ask} for {@code player} — always an answer, "nothing" included. */
    public static StagePreviewPacket compose(ServerPlayer player, StagePreviewRequestPacket ask) {
        if (!player.hasPermissions(PERMISSION_LEVEL)) return StagePreviewPacket.none(ask);
        ServerLevel level = player.serverLevel();
        if (!EditorWorldLayout.isEditorWorld(level)) return StagePreviewPacket.none(ask);
        if (!StageStore.exists(ask.stageId())) return StagePreviewPacket.none(ask);
        Optional<CarriageVariant> variant = CarriageVariantRegistry.find(ask.carriageId());
        if (variant.isEmpty()) return StagePreviewPacket.none(ask);
        CarriageDims dims = DungeonTrainWorldData.get(level.getServer().overworld()).dims();
        try {
            byte[] bytes = stampAndCapture(level, variant.get(), dims, ask.seed(), ask.stageId());
            if (bytes == null || bytes.length > StagePreviewPacket.MAX_BYTES) return StagePreviewPacket.none(ask);
            return new StagePreviewPacket(ask.stageId(), ask.carriageId(), ask.seed(), true, bytes);
        } catch (RuntimeException | IOException e) {
            LOGGER.warn("[DungeonTrain] Stage preview failed for stage={} carriage={}: {}",
                ask.stageId(), ask.carriageId(), e.toString());
            return StagePreviewPacket.none(ask);
        }
    }

    /** Stamp at the scratch origin, capture the footprint as a template, erase, and serialise. */
    static byte[] stampAndCapture(ServerLevel level, CarriageVariant variant, CarriageDims dims,
                                  long seed, String stageId) throws IOException {
        BlockPos origin = SCRATCH_ORIGIN;
        Vec3i size = new Vec3i(dims.length(), dims.height(), dims.width());
        // Ensure the scratch chunks are here before any block goes down.
        level.getChunk(origin.getX() >> 4, origin.getZ() >> 4);
        level.getChunk((origin.getX() + size.getX()) >> 4, (origin.getZ() + size.getZ()) >> 4);
        try {
            erase(level, origin, size);
            CarriagePlacer.placeStagePreview(level, origin, variant, dims, seed, stageId);
            StructureTemplate template = TemplateDecor.capture(level, origin, size, Blocks.AIR);
            CompoundTag tag = template.save(new CompoundTag());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(out));   // uncompressed, as BuilderRelayPreview.decode reads
            return out.toByteArray();
        } finally {
            erase(level, origin, size);
        }
    }

    private static void erase(ServerLevel level, BlockPos origin, Vec3i size) {
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dy = 0; dy < size.getY(); dy++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).isAir()) {
                        SilentBlockOps.setBlockSilent(level, pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        // Anything the stamp spawned (part decor rides along with a part template) goes with it.
        level.getEntities((net.minecraft.world.entity.Entity) null,
            net.minecraft.world.phys.AABB.encapsulatingFullBlocks(origin, origin.offset(size)), e -> !(e instanceof ServerPlayer))
            .forEach(net.minecraft.world.entity.Entity::discard);
    }
}
