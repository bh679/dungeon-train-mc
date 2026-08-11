package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderBounds;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.client.builder.BuilderBoundsState;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.CarriageWeights;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: the Train Builder build volumes — one box per parked carriage.
 *
 * <p>The client can't work these out for itself: they depend on which builder mode created the
 * world, which is a title-screen choice recorded only in
 * {@link DungeonTrainWorldData#builderMode()}. Sent on join and again right after
 * {@link BuilderSetupPacket} stamps a fresh world (the first join beats the stamp).</p>
 *
 * <p>An empty list is meaningful and is sent deliberately: it's how a non-builder world, or a
 * builder mode with no carriages, tells the client to stop washing anything.</p>
 *
 * <p>It also carries the build's name and mirror setting — everything the pause menu needs to know
 * about the current build. Those ride along here rather than on a packet of their own because they
 * change at exactly the same moments the volumes do (entry, New, mode switch), plus one more: the
 * mirror command resends this after a toggle.</p>
 *
 * @param buildName what the build saves as; empty means an unnamed draft
 * @param mirrorMask packed {@link games.brennan.dungeontrain.builder.BuilderMirrorFlags}
 */
public record BuilderBoundsPacket(List<BoundingBox> volumes, String modeId, String buildName,
                                  int mirrorMask, String subTypeId, String partKindId,
                                  String stageId, int weight)
        implements CustomPacketPayload {

    /** Guards against a malformed or hostile payload allocating an unbounded list. */
    private static final int MAX_VOLUMES = 64;

    public static final Type<BuilderBoundsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_bounds"));

    public static final StreamCodec<FriendlyByteBuf, BuilderBoundsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeUtf(packet.modeId, 32);
                buf.writeUtf(packet.buildName, 64);
                buf.writeVarInt(packet.mirrorMask);
                buf.writeUtf(packet.subTypeId, 32);
                buf.writeUtf(packet.partKindId, 16);
                buf.writeUtf(packet.stageId, 32);
                buf.writeVarInt(packet.weight + 1);   // -1 = "doesn't apply"; varint wants non-negative
                buf.writeVarInt(Math.min(packet.volumes.size(), MAX_VOLUMES));
                for (int i = 0; i < Math.min(packet.volumes.size(), MAX_VOLUMES); i++) {
                    BoundingBox b = packet.volumes.get(i);
                    buf.writeVarInt(b.minX()); buf.writeVarInt(b.minY()); buf.writeVarInt(b.minZ());
                    buf.writeVarInt(b.maxX()); buf.writeVarInt(b.maxY()); buf.writeVarInt(b.maxZ());
                }
            },
            buf -> {
                String modeId = buf.readUtf(32);
                String buildName = buf.readUtf(64);
                int mirrorMask = buf.readVarInt();
                String subTypeId = buf.readUtf(32);
                String partKindId = buf.readUtf(16);
                String stageId = buf.readUtf(32);
                int weight = buf.readVarInt() - 1;
                int count = Math.min(buf.readVarInt(), MAX_VOLUMES);
                List<BoundingBox> boxes = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    boxes.add(new BoundingBox(
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
                }
                return new BuilderBoundsPacket(boxes, modeId, buildName, mirrorMask,
                        subTypeId, partKindId, stageId, weight);
            }
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Build the packet for a player's current world — empty outside a builder world. */
    public static BuilderBoundsPacket forLevel(ServerLevel overworld) {
        if (!overworld.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return new BuilderBoundsPacket(List.of(), "", "", 0, "", "", "", -1);
        }
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        int carriages = BuilderMode.fromId(data.builderMode())
            .map(BuilderMode::carriageCount)
            .orElse(0);
        CarriageDims dims = data.dims();
        String modeId = data.builderMode() == null ? "" : data.builderMode();
        // Empty name = an unnamed draft; the client uses it to decide whether Save can write
        // straight away or has to ask for a name first.
        String buildName = data.builderName() == null ? "" : data.builderName();
        return new BuilderBoundsPacket(BuilderBounds.buildVolumes(carriages, dims), modeId,
                buildName, data.builderMirror().pack(),
                orEmpty(data.builderSubType()), orEmpty(data.builderPartKind()),
                orEmpty(data.builderStage()), weightOf(data));
    }

    /**
     * The saved template's pick weight, or {@code -1} when the line doesn't apply.
     *
     * <p>Only carriages: a draft has no template to have a weight, and contents and parts keep
     * theirs in their own stores — reading the carriage weights for a part id would report a number
     * belonging to something else entirely.</p>
     */
    private static int weightOf(DungeonTrainWorldData data) {
        String name = data.builderName();
        if (name == null || name.isEmpty()) {
            return -1;
        }
        boolean isCarriage = data.builderSubType() == null
                || BuilderNewOptions.SubType.WHOLE_CARRIAGE.id().equals(data.builderSubType());
        return isCarriage && CarriageVariantRegistry.find(name).isPresent()
                ? CarriageWeights.current().weightFor(name)
                : -1;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    public static void sendTo(ServerPlayer player, ServerLevel overworld) {
        DungeonTrainNet.sendTo(player, forLevel(overworld));
    }

    public static void handle(BuilderBoundsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> BuilderBoundsState.set(packet));
    }
}
