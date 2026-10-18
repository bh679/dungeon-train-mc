package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.PetProtectionMirror;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: whether this player's own pets are safe from them, sent on login and again
 * whenever they toggle it while connected.
 *
 * <p>The preference is a CLIENT-scope config ({@code ClientDisplayConfig.PROTECT_OWN_PETS}) but the
 * hit is decided server-side in {@code OwnPetProtectionEvents}, so the client has to tell the
 * server, exactly as {@link BookAuthorChatSyncPacket} does for the book-author line.</p>
 */
public record PetProtectionSyncPacket(boolean enabled) implements CustomPacketPayload {

    public static final Type<PetProtectionSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "pet_protection_sync"));

    public static final StreamCodec<FriendlyByteBuf, PetProtectionSyncPacket> STREAM_CODEC =
        StreamCodec.of(PetProtectionSyncPacket::encode, PetProtectionSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, PetProtectionSyncPacket pkt) {
        buf.writeBoolean(pkt.enabled());
    }

    private static PetProtectionSyncPacket decode(FriendlyByteBuf buf) {
        return new PetProtectionSyncPacket(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PetProtectionSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            PetProtectionMirror.set(player, packet.enabled());
        });
    }
}
