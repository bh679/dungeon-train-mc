package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.PrefabDeletes;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * C→S: delete a saved prefab — sent when the player confirms the prompt raised by Cmd-clicking
 * the prefab in the creative menu. The client only offers the prompt for entries synced as
 * deletable; the server re-checks {@link PrefabDeletes#canDelete} and OP here regardless.
 */
public record DeletePrefabPacket(PrefabDeletes.Kind kind, String id) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PrefabDeletes.Kind[] KINDS = PrefabDeletes.Kind.values();

    public static final Type<DeletePrefabPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "delete_prefab"));

    public static final StreamCodec<FriendlyByteBuf, DeletePrefabPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            DeletePrefabPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(kind.ordinal());
        buf.writeUtf(id, 64);
    }

    public static DeletePrefabPacket decode(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        String id = buf.readUtf(64);
        PrefabDeletes.Kind kind = ordinal >= 0 && ordinal < KINDS.length ? KINDS[ordinal] : null;
        return new DeletePrefabPacket(kind, id);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DeletePrefabPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (packet.kind() == null || !PrefabDeletes.isValidName(packet.kind(), packet.id())) return;
            if (!player.hasPermissions(2)) {
                actionBar(player, Component.translatable("chat.dungeontrain.prefab_delete.needs_op"),
                    ChatFormatting.RED);
                return;
            }
            if (!PrefabDeletes.canDelete(packet.kind(), packet.id())) {
                actionBar(player, Component.translatable("chat.dungeontrain.prefab_delete.not_yours", packet.id()),
                    ChatFormatting.RED);
                return;
            }
            try {
                PrefabDeletes.delete(packet.kind(), packet.id());
                actionBar(player, Component.translatable("chat.dungeontrain.prefab_delete.done", packet.id()),
                    ChatFormatting.GREEN);
                PacketDistributor.sendToAllPlayers(PrefabRegistrySyncPacket.fromRegistries());
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Delete of {} prefab '{}' failed: {}",
                    packet.kind(), packet.id(), e.toString());
                actionBar(player, Component.translatable("chat.dungeontrain.prefab_delete.failed", packet.id()),
                    ChatFormatting.RED);
            }
        });
    }

    private static void actionBar(ServerPlayer player, Component text, ChatFormatting colour) {
        player.displayClientMessage(text.copy().withStyle(colour), true);
    }
}
