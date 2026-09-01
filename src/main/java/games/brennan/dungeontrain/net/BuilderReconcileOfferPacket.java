package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.BuilderReconcileClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the relay has lost some of your builds, and they can be put back.
 *
 * <p>Counts only. The relay call and the disk checks are server-side — the mod's relay client always
 * is — so the client is told how many builds are recoverable from each tier and nothing else. What it
 * does with that is ask; {@link BuilderReconcileStartPacket} is the answer coming back.</p>
 *
 * @param onDisk     builds whose template is still in the live store — unambiguous, offered by default
 * @param backupOnly builds whose only surviving copy is inside a backup archive, which may equally
 *                   be builds the player deleted on purpose. Offered separately, off by default.
 */
public record BuilderReconcileOfferPacket(int onDisk, int backupOnly) implements CustomPacketPayload {

    public static final Type<BuilderReconcileOfferPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_reconcile_offer"));

    public static final StreamCodec<FriendlyByteBuf, BuilderReconcileOfferPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.onDisk);
                buf.writeVarInt(packet.backupOnly);
            },
            buf -> new BuilderReconcileOfferPacket(buf.readVarInt(), buf.readVarInt())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderReconcileOfferPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> BuilderReconcileClient.offer(packet.onDisk, packet.backupOnly));
    }
}
