package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.PrefabAnchorScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * S→C: open the prefab-anchor binding screen for the anchor at {@code pos}, carrying the current
 * binding and every prefab name the server knows. Sent when a player right-clicks a
 * {@code PrefabAnchorBlock}; the screen answers with {@link SetPrefabAnchorPacket}.
 *
 * <p>The name list rides along rather than living in a separate sync so the client never shows a
 * stale roster — the server's registry is the authority and this is the one moment it matters.</p>
 */
public record OpenPrefabAnchorPacket(BlockPos pos, String current, List<String> names)
        implements CustomPacketPayload {

    private static final int MAX_NAME = 64;
    private static final int MAX_NAMES = 4096;

    public static final Type<OpenPrefabAnchorPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "open_prefab_anchor"));

    public static final StreamCodec<FriendlyByteBuf, OpenPrefabAnchorPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), OpenPrefabAnchorPacket::decode);

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(current, MAX_NAME);
        buf.writeVarInt(Math.min(names.size(), MAX_NAMES));
        for (int i = 0; i < names.size() && i < MAX_NAMES; i++) buf.writeUtf(names.get(i), MAX_NAME);
    }

    public static OpenPrefabAnchorPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String current = buf.readUtf(MAX_NAME);
        int n = Math.min(buf.readVarInt(), MAX_NAMES);
        List<String> names = new ArrayList<>(n);
        for (int i = 0; i < n; i++) names.add(buf.readUtf(MAX_NAME));
        return new OpenPrefabAnchorPacket(pos, current, List.copyOf(names));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenPrefabAnchorPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PrefabAnchorScreen.open(packet.pos(), packet.current(), packet.names()));
    }
}
