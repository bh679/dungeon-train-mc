package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderNoteEdits;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import games.brennan.dungeontrain.builder.relay.SubmitNote;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: replace a build's Submit for Review answers — the editor's Edit answers.
 *
 * <p>Names the build's owner and pool, because the developer edits other people's builds and the
 * server has to know whose it is to decide which way the write goes ({@link BuilderNoteEdits}). The
 * answer is a chat line either way; the screen has already shown the new answers optimistically.</p>
 */
public record BuilderNoteEditPacket(int relayId, String ownerUuid, boolean live, SubmitNote note)
        implements CustomPacketPayload {

    public BuilderNoteEditPacket {
        ownerUuid = ownerUuid == null ? "" : ownerUuid;
        note = note == null ? SubmitNote.EMPTY : note;
    }

    public static final Type<BuilderNoteEditPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_note_edit"));

    public static final StreamCodec<FriendlyByteBuf, BuilderNoteEditPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeUtf(packet.ownerUuid, 48);
                buf.writeBoolean(packet.live);
                buf.writeUtf(packet.note.redstone(), BuilderProfileActionPacket.NOTE_MAX);
                buf.writeUtf(packet.note.loot(), BuilderProfileActionPacket.NOTE_MAX);
                buf.writeUtf(packet.note.notes(), BuilderProfileActionPacket.NOTE_MAX);
            },
            buf -> new BuilderNoteEditPacket(buf.readVarInt(), buf.readUtf(48), buf.readBoolean(),
                new SubmitNote(buf.readUtf(BuilderProfileActionPacket.NOTE_MAX),
                    buf.readUtf(BuilderProfileActionPacket.NOTE_MAX),
                    buf.readUtf(BuilderProfileActionPacket.NOTE_MAX)))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderNoteEditPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || player.getServer() == null) return;
            if (!BuilderRelayUpload.canUpload(player)) return;
            ServerLevel level = player.getServer().overworld();
            String owner = packet.ownerUuid.isEmpty() ? player.getUUID().toString() : packet.ownerUuid;
            boolean live = BuilderProfileRequestPacket.liveRequested(packet.live);
            BuilderNoteEdits.edit(player, level, packet.relayId, owner, live, packet.note)
                .thenAccept(message -> player.getServer().execute(() -> {
                    if (!player.hasDisconnected()) player.sendSystemMessage(message);
                }));
        });
    }
}
