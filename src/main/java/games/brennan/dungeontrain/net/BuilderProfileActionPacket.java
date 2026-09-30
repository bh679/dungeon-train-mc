package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import games.brennan.dungeontrain.builder.relay.SubmitNote;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Arrays;
import java.util.Objects;

/**
 * Client → server: put one of my builds on the train, or take it back off.
 *
 * <p>Carries only the relay id and the direction. The credential that authorises it — the build's owner
 * secret — is deliberately not on the wire: it lives in the world's saved data, and the server looks it
 * up by id. A client that names a build this world never uploaded gets told so, because there is no
 * secret to find, which is also what stops the packet being a way to publish somebody else's work.</p>
 *
 * <p>{@code note} is what the author wants the reviewer to know: how the redstone works and how to
 * test it, what the loot is, and anything else ({@link SubmitNote}). Free text, optional, and only
 * meaningful on a submit: a withdraw carries an empty one. The server trims and caps each field again
 * before it goes anywhere ({@code BuilderRelayUpload.cleanNote}), so the wire cap here is a bound on
 * the packet, not the rule.</p>
 *
 * <p>{@code render} is the client's picture of the build (PNG, {@code BuildRenderCapture}) for the
 * Discord announcement a submit posts; empty when the client had nothing baked to draw, and always
 * empty on a withdraw. The server never needs it to act, so a missing picture costs the post its
 * image and nothing else.</p>
 */
public record BuilderProfileActionPacket(int relayId, boolean publish, SubmitNote note, byte[] render)
        implements CustomPacketPayload {

    /** Characters per note field the packet will carry — the same cap the screen enforces while typing. */
    public static final int NOTE_MAX = 1000;

    /** Bytes of rendering the packet will carry — the cap {@code DeathPhotoPacket} sets for a ride photo. */
    public static final int RENDER_MAX = 1024 * 1024;

    private static final byte[] NO_RENDER = new byte[0];

    /** A submit or withdraw with nothing to tell the reviewer. */
    public BuilderProfileActionPacket(int relayId, boolean publish) {
        this(relayId, publish, SubmitNote.EMPTY, null);
    }

    /** A submit with a note but no picture — what a withdraw, or a client with nothing baked, sends. */
    public BuilderProfileActionPacket(int relayId, boolean publish, SubmitNote note) {
        this(relayId, publish, note, null);
    }

    public BuilderProfileActionPacket {
        note = note == null ? SubmitNote.EMPTY : note;
        render = render == null ? NO_RENDER : render;
    }

    /** True when the client sent a picture of the build along. */
    public boolean hasRender() {
        return render.length > 0;
    }

    // A record's equals/hashCode compare an array by identity, which makes every decoded packet
    // unequal to the one encoded; compare the bytes instead so the wire round-trip is testable.
    @Override
    public boolean equals(Object o) {
        return o instanceof BuilderProfileActionPacket p && p.relayId == relayId && p.publish == publish
                && p.note.equals(note) && Arrays.equals(p.render, render);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relayId, publish, note, Arrays.hashCode(render));
    }

    @Override
    public String toString() {
        return "BuilderProfileActionPacket[relayId=" + relayId + ", publish=" + publish + ", note=" + note
                + ", render=" + render.length + " bytes]";
    }

    public static final Type<BuilderProfileActionPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_profile_action"));

    public static final StreamCodec<FriendlyByteBuf, BuilderProfileActionPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeBoolean(packet.publish);
                buf.writeUtf(packet.note.redstone(), NOTE_MAX);
                buf.writeUtf(packet.note.loot(), NOTE_MAX);
                buf.writeUtf(packet.note.notes(), NOTE_MAX);
                buf.writeByteArray(packet.render);
            },
            buf -> new BuilderProfileActionPacket(buf.readVarInt(), buf.readBoolean(),
                new SubmitNote(buf.readUtf(NOTE_MAX), buf.readUtf(NOTE_MAX), buf.readUtf(NOTE_MAX)),
                buf.readByteArray(RENDER_MAX))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderProfileActionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (player.getServer() == null || !BuilderRelayUpload.canUpload(player)) return;
            ServerLevel level = player.getServer().overworld();
            BuilderRelayUpload.submitToTrain(player, level, packet.relayId, packet.publish,
                            packet.note.cleaned(), packet.publish ? packet.render : null)
                    .thenAccept(message -> player.getServer().execute(() -> {
                        if (player.hasDisconnected()) return;
                        player.sendSystemMessage(message);
                        // Re-read the profile so the screen shows what actually happened rather than
                        // what was asked for — the relay may have refused (a build in use elsewhere).
                        BuilderProfileRequestPacket.handle(new BuilderProfileRequestPacket(), ctx);
                    }));
        });
    }
}
