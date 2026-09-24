package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.builder.BuilderSubmitHintsRequests;
import games.brennan.dungeontrain.editor.SubmitHints;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Server → client: the answer to a {@link BuilderSubmitHintsRequestPacket} — which extra questions to ask. */
public record BuilderSubmitHintsPacket(int relayId, SubmitHints.Hints hints) implements CustomPacketPayload {

    public BuilderSubmitHintsPacket {
        hints = hints == null ? SubmitHints.Hints.NONE : hints;
    }

    public static final Type<BuilderSubmitHintsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_submit_hints"));

    /** Kinds of block per list the packet carries — past this the screen has no room to draw them anyway. */
    static final int MAX_FOUND = 32;
    private static final int MAX_DETAIL = 256;

    public static final StreamCodec<FriendlyByteBuf, BuilderSubmitHintsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                writeFound(buf, packet.hints.redstone());
                writeFound(buf, packet.hints.loot());
            },
            buf -> new BuilderSubmitHintsPacket(buf.readVarInt(),
                new SubmitHints.Hints(readFound(buf), readFound(buf)))
        );

    private static void writeFound(FriendlyByteBuf buf, List<SubmitHints.Found> found) {
        int n = Math.min(found.size(), MAX_FOUND);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            SubmitHints.Found f = found.get(i);
            buf.writeResourceLocation(BuiltInRegistries.BLOCK.getKey(f.block()));
            buf.writeVarInt(f.count());
            buf.writeEnum(f.kind());
            String detail = f.detail();
            buf.writeUtf(detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail, MAX_DETAIL);
            buf.writeDouble(f.value());
        }
    }

    private static List<SubmitHints.Found> readFound(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_FOUND);
        List<SubmitHints.Found> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Block block = BuiltInRegistries.BLOCK.get(buf.readResourceLocation());
            out.add(new SubmitHints.Found(block, buf.readVarInt(), buf.readEnum(SubmitHints.Kind.class),
                buf.readUtf(MAX_DETAIL), buf.readDouble()));
        }
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderSubmitHintsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> BuilderSubmitHintsRequests.accept(packet.relayId, packet.hints));
    }
}
