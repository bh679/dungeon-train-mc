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
 * what kind of place it is ("cart type"), the interior contents parent ("content type"), and the
 * group member that parent resolved to ("sub variant").
 *
 * <p>Sent alongside {@link CarriageIndexPacket} on a carriage-boundary crossing, but <b>only to
 * players who may open the panel</b>. It is deliberately separate rather than three more fields on
 * {@code CarriageIndexPacket}, which goes to everyone: the same reasoning that keeps the world seed
 * off an ungranted client applies here, and it keeps three strings per crossing off the wire for
 * every player who would never see them.</p>
 *
 * <p>{@code subVariantId} is empty when the parent's group draw landed on the parent's own
 * contents (the synthetic "self" member) or when it has no group sidecar at all — there is no
 * sub-variant to name in either case.</p>
 *
 * <p>{@code cartType} is a resolved label, not always a variant id: an ordinary carriage reports
 * the variant it rolled, but a flatbed pad, a portal corridor and a dimensional carriage report
 * what they are, since none of them roll a variant at all.</p>
 *
 * <p>{@code copy} names which of a portal corridor's two stacked copies the player is standing in
 * — {@code near} or {@code far} — and is empty anywhere else, including in the corridor that rides
 * the train. Empty therefore means "not in a copy", which is itself the answer.</p>
 *
 * <p>The contents ids are what the carriage index <em>rolls to</em>. A slot filled from the
 * shared-carriage relay pool holds another player's build placed verbatim, so for those carriages
 * these name what would have generated rather than what is standing.</p>
 */
public record TrainDebugCarriagePacket(boolean present, int pIdx, String cartType,
                                       String contentsId, String subVariantId, String copy)
        implements CustomPacketPayload {

    public static final Type<TrainDebugCarriagePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "train_debug_carriage"));

    public static final StreamCodec<FriendlyByteBuf, TrainDebugCarriagePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            TrainDebugCarriagePacket::decode
        );

    public TrainDebugCarriagePacket {
        cartType = cartType == null ? "" : cartType;
        copy = copy == null ? "" : copy;
        contentsId = contentsId == null ? "" : contentsId;
        subVariantId = subVariantId == null ? "" : subVariantId;
    }

    /** The "not on a train" form — carries no ids. */
    public static TrainDebugCarriagePacket absent() {
        return new TrainDebugCarriagePacket(false, 0, "", "", "", "");
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(present);
        if (present) {
            buf.writeVarInt(pIdx);
            buf.writeUtf(cartType);
            buf.writeUtf(contentsId);
            buf.writeUtf(subVariantId);
            buf.writeUtf(copy);
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
            packet.present, packet.pIdx, packet.cartType, packet.contentsId, packet.subVariantId,
            packet.copy));
    }
}
