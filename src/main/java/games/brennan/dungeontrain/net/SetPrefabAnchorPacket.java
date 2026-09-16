package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.prefab.PrefabAnchorBlockEntity;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/**
 * C→S: bind the prefab anchor at {@code pos} to prefab {@code name} (empty to unbind). The reply
 * to {@link OpenPrefabAnchorPacket}. The server validates OP, that the block is still an anchor
 * within reach, and that the name is a registered prefab — the client's list may be stale.
 */
public record SetPrefabAnchorPacket(BlockPos pos, String name) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_NAME = 64;
    private static final double MAX_REACH_SQ = 64.0 * 64.0;

    public static final Type<SetPrefabAnchorPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "set_prefab_anchor"));

    public static final StreamCodec<FriendlyByteBuf, SetPrefabAnchorPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), SetPrefabAnchorPacket::decode);

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(name, MAX_NAME);
    }

    public static SetPrefabAnchorPacket decode(FriendlyByteBuf buf) {
        return new SetPrefabAnchorPacket(buf.readBlockPos(), buf.readUtf(MAX_NAME));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetPrefabAnchorPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (!(p instanceof ServerPlayer player)) return;
            if (!player.hasPermissions(2)) {
                feedback(player, "Binding a prefab anchor requires OP", ChatFormatting.RED);
                return;
            }
            if (player.blockPosition().distSqr(packet.pos()) > MAX_REACH_SQ) {
                feedback(player, "Anchor is out of reach", ChatFormatting.RED);
                return;
            }
            if (!(player.serverLevel().getBlockEntity(packet.pos()) instanceof PrefabAnchorBlockEntity anchor)) {
                feedback(player, "No prefab anchor at " + packet.pos().toShortString(), ChatFormatting.RED);
                return;
            }
            String name = packet.name().trim();
            if (!name.isEmpty() && !TrackVariantRegistry.contains(TrackKind.PREFAB, name)) {
                feedback(player, "No prefab named '" + name + "'", ChatFormatting.RED);
                return;
            }
            anchor.setPrefab(name);
            LOGGER.info("[DungeonTrain] {} bound prefab anchor at {} to '{}'",
                player.getName().getString(), packet.pos().toShortString(), name);
            feedback(player, name.isEmpty() ? "Prefab anchor unbound" : "Prefab anchor → " + name,
                ChatFormatting.GREEN);
        });
    }

    private static void feedback(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(Component.literal(text).withStyle(colour), true);
    }
}
