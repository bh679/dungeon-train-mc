package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.TrainDebugState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: what the player's current carriage actually is, for the F3+4 debug panel —
 * its shell variant ("cart type"), the interior contents parent ("content type"), and the group
 * member that parent resolved to ("sub variant").
 *
 * <p>Sent alongside {@link CarriageIndexPacket} on a carriage-boundary crossing, but <b>only to
 * players who may open the panel</b>. It is deliberately separate rather than three more fields on
 * {@code CarriageIndexPacket}, which goes to everyone: the same reasoning that keeps the world seed
 * off an ungranted client applies here, and it keeps three strings per crossing off the wire for
 * every player who would never see them.</p>
 *
 * <p>{@code flip} is which axes that interior's stamp came out flipped along, as
 * {@code ContentsFlip.label} renders them ({@code none}, {@code X}, {@code X+Z}, …) — the one thing
 * on the panel you cannot read off the standing carriage by eye.</p>
 *
 * <p>{@code subVariantId} is empty when the parent's group draw landed on the parent's own
 * contents (the synthetic "self" member) or when it has no group sidecar at all — there is no
 * sub-variant to name in either case.</p>
 *
 * <p>All three ids are what the carriage index <em>rolls to</em>. A slot filled from the
 * shared-carriage relay pool holds another player's build placed verbatim, so for those carriages
 * these name what would have generated rather than what is standing.</p>
 */
public record TrainDebugCarriagePacket(boolean present, int pIdx, String variantId,
                                       String contentsId, String subVariantId, String flip)
        implements CustomPacketPayload {

    public static final Type<TrainDebugCarriagePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "train_debug_carriage"));

    public static final StreamCodec<FriendlyByteBuf, TrainDebugCarriagePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            TrainDebugCarriagePacket::decode
        );

    public TrainDebugCarriagePacket {
        variantId = variantId == null ? "" : variantId;
        contentsId = contentsId == null ? "" : contentsId;
        subVariantId = subVariantId == null ? "" : subVariantId;
        flip = flip == null ? "" : flip;
    }

    /** Back-compat constructor from before the flip was reported. */
    public TrainDebugCarriagePacket(boolean present, int pIdx, String variantId,
                                    String contentsId, String subVariantId) {
        this(present, pIdx, variantId, contentsId, subVariantId, "");
    }

    /** The "not on a train" form — carries no ids. */
    public static TrainDebugCarriagePacket absent() {
        return new TrainDebugCarriagePacket(false, 0, "", "", "", "");
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(present);
        if (present) {
            buf.writeVarInt(pIdx);
            buf.writeUtf(variantId);
            buf.writeUtf(contentsId);
            buf.writeUtf(subVariantId);
            buf.writeUtf(flip);
        }
    }

    public static TrainDebugCarriagePacket decode(FriendlyByteBuf buf) {
        boolean present = buf.readBoolean();
        if (!present) {
            return absent();
        }
        return new TrainDebugCarriagePacket(
            true, buf.readVarInt(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TrainDebugCarriagePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> TrainDebugState.setCarriage(
            packet.present, packet.pIdx, packet.variantId, packet.contentsId, packet.subVariantId,
            packet.flip));
    }
}
