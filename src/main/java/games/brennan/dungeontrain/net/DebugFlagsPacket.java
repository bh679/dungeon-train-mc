package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.DebugFlagsState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client snapshot of the debug flags exposed in the in-world
 * Debug menu. Sent on player join (so the menu's Toggle states render
 * correctly the first time it's opened) and again whenever a flag is
 * toggled (so all connected clients immediately see the new state).
 *
 * <p>The five wireframe flags ({@code gapCubes}, {@code gapLine},
 * {@code nextSpawn}, {@code collision}, {@code hudDistance}) each gate one
 * world-space overlay or HUD line — they were collapsed into a single
 * switch in earlier versions but the player wants per-overlay control to
 * isolate one debugging concern at a time. {@code manualSpawnMode} mirrors
 * {@link games.brennan.dungeontrain.train.TrainCarriageAppender#MANUAL_MODE}
 * so the menu's Auto / Manual toggle reflects current server truth.</p>
 *
 * <p>The two chat-log flags ({@code chatTrainSpawn}, {@code chatCollision})
 * gate the in-game CHAT broadcasts emitted by the spawn / collision
 * codepaths — independent of the wireframe flags so a player can keep
 * the visual collision overlay on while silencing the chat warnings (or
 * vice versa).</p>
 *
 * <p>Wire field order is fixed and append-only: writer and reader lambdas
 * must consume the eight booleans in the same sequence ({@code gapCubes},
 * {@code gapLine}, {@code nextSpawn}, {@code collision}, {@code hudDistance},
 * {@code manualSpawnMode}, {@code chatTrainSpawn}, {@code chatCollision}).
 * Mismatched order would silently scramble client-side state. New flags
 * MUST be appended at the end (never inserted) so existing clients see
 * a consistent prefix.</p>
 */
public record DebugFlagsPacket(
    boolean gapCubes,
    boolean gapLine,
    boolean nextSpawn,
    boolean collision,
    boolean hudDistance,
    boolean manualSpawnMode,
    boolean chatTrainSpawn,
    boolean chatCollision
) implements CustomPacketPayload {

    public static final Type<DebugFlagsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "debug_flags"));

    public static final StreamCodec<FriendlyByteBuf, DebugFlagsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeBoolean(packet.gapCubes);
                buf.writeBoolean(packet.gapLine);
                buf.writeBoolean(packet.nextSpawn);
                buf.writeBoolean(packet.collision);
                buf.writeBoolean(packet.hudDistance);
                buf.writeBoolean(packet.manualSpawnMode);
                buf.writeBoolean(packet.chatTrainSpawn);
                buf.writeBoolean(packet.chatCollision);
            },
            buf -> new DebugFlagsPacket(
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean()
            )
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DebugFlagsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> DebugFlagsState.applyServerState(packet));
    }
}
