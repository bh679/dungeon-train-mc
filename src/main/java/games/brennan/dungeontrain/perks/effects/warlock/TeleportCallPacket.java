package games.brennan.dungeontrain.perks.effects.warlock;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Client to server, for Teleport Player ({@link TeleportCalls}): {@link #LIST} asks for who can be
 * called, {@link #CALL} calls a player or PlayerMob ({@code kind}, {@code id}), {@link #ACCEPT} accepts
 * the call from player {@code id}. The server checks the perk again for each.
 */
public record TeleportCallPacket(byte action, byte kind, UUID id) implements CustomPacketPayload {

    public static final byte LIST = 0, CALL = 1, ACCEPT = 2;
    private static final UUID NONE = new UUID(0L, 0L);

    public static TeleportCallPacket list() {
        return new TeleportCallPacket(LIST, (byte) 0, NONE);
    }

    public static TeleportCallPacket call(byte kind, UUID id) {
        return new TeleportCallPacket(CALL, kind, id);
    }

    public static TeleportCallPacket accept(UUID caller) {
        return new TeleportCallPacket(ACCEPT, (byte) 0, caller);
    }

    public static final Type<TeleportCallPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "teleport_player_call"));

    public static final StreamCodec<FriendlyByteBuf, TeleportCallPacket> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeByte(p.action);
            buf.writeByte(p.kind);
            buf.writeUUID(p.id);
        },
        buf -> new TeleportCallPacket(buf.readByte(), buf.readByte(), buf.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TeleportCallPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            switch (packet.action()) {
                case LIST -> TeleportCalls.sendList(player);
                case CALL -> TeleportCalls.call(player, packet.kind(), packet.id());
                case ACCEPT -> TeleportCalls.accept(player, packet.id());
                default -> { }
            }
        });
    }
}
