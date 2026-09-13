package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: Stage Palette panel ops. {@code SET_OVERRIDE} assigns the player's <b>held
 * block</b> to placeholder {@code name} for {@code stageId} (an empty hand clears the override —
 * the same "from the hand" model as {@link StagePanelEditPacket.Op#SWAP_BLOCK}); {@code SET_WOOD}
 * / {@code SET_STONE} pick the family the held block belongs to (empty hand unlocks the family so
 * the next bake re-detects it); {@code REBAKE} re-derives the stage's palette, keeping overrides.
 *
 * <p>The server validates OP≥2 and that the player's stage panel is open on {@code stageId}.</p>
 */
public record StagePaletteEditPacket(Op op, String stageId, String name, boolean fromScreen)
        implements CustomPacketPayload {

    /** The world-space panel's shape: the stage's panel must be open for the player. */
    public StagePaletteEditPacket(Op op, String stageId, String name) {
        this(op, stageId, name, false);
    }

    // NOTE: ordinals are the wire format (encode writes op.ordinal()) — only ever APPEND.
    public enum Op { SET_OVERRIDE, CLEAR_OVERRIDE, SET_WOOD, SET_STONE, REBAKE }

    public static final Type<StagePaletteEditPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "stage_palette_edit"));

    public static final StreamCodec<FriendlyByteBuf, StagePaletteEditPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), StagePaletteEditPacket::decode);

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(op.ordinal());
        buf.writeUtf(stageId == null ? "" : stageId);
        buf.writeUtf(name == null ? "" : name);
        buf.writeBoolean(fromScreen);
    }

    public static StagePaletteEditPacket decode(FriendlyByteBuf buf) {
        Op op = Op.values()[buf.readByte()];
        return new StagePaletteEditPacket(op, buf.readUtf(64), buf.readUtf(64), buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StagePaletteEditPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p instanceof ServerPlayer sender) {
                games.brennan.dungeontrain.editor.EditorEditRecorder.notePendingConfig(sender, "stage");
                games.brennan.dungeontrain.editor.StagePanelController.applyPaletteEdit(sender, packet);
            }
        });
    }
}
