package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.builder.BuilderDirtyState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: how many builder carriages have unsaved changes.
 *
 * <p>Two jobs, because they're the same question asked at different moments:</p>
 * <ul>
 *   <li>{@code targetModeId} empty — a plain state push. The pause menu asks for this when it
 *       opens, and the answer is what tints <b>Save</b> green.</li>
 *   <li>{@code targetModeId} set — the player tried to switch modes and there is unsaved work, so
 *       the client opens the Save / Discard / Go Back prompt for that target mode.</li>
 * </ul>
 */
public record BuilderDirtyPacket(int dirtyCount, String targetModeId) implements CustomPacketPayload {

    public static final Type<BuilderDirtyPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_dirty"));

    public static final StreamCodec<FriendlyByteBuf, BuilderDirtyPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.dirtyCount);
                buf.writeUtf(packet.targetModeId, 32);
            },
            buf -> new BuilderDirtyPacket(buf.readVarInt(), buf.readUtf(32))
        );

    /** A state push with no pending switch. */
    public static BuilderDirtyPacket state(int dirtyCount) {
        return new BuilderDirtyPacket(dirtyCount, "");
    }

    public boolean isSwitchPrompt() {
        return !targetModeId.isEmpty();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderDirtyPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> BuilderDirtyState.accept(packet));
    }
}
