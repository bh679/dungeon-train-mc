package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.DifficultyChangeConfirmClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: a difficulty change was intercepted and held; ask the player to confirm before
 * it commits, because committing swaps their whole carried loadout (inventory + XP) and their Ender
 * Chest for the target difficulty's — see {@code DifficultyChangeConfirmEvents}.
 *
 * <p>Carries both difficulties so the prompt can name them, plus {@code targetEmpty}: true when the
 * player has never played the target difficulty, i.e. it starts them with nothing. Only the server
 * can know that (it holds the loadout locker), so it is decided there and shipped here.</p>
 */
public record ShowDifficultyConfirmPacket(Difficulty from, Difficulty to, boolean targetEmpty)
    implements CustomPacketPayload {

    public static final Type<ShowDifficultyConfirmPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "show_difficulty_confirm"));

    public static final StreamCodec<FriendlyByteBuf, ShowDifficultyConfirmPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, pkt) -> {
                buf.writeByte(pkt.from().getId());
                buf.writeByte(pkt.to().getId());
                buf.writeBoolean(pkt.targetEmpty());
            },
            buf -> new ShowDifficultyConfirmPacket(
                Difficulty.byId(buf.readByte()), Difficulty.byId(buf.readByte()), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound — only runs on the physical client (mirrors {@link ShowFreePlayConfirmPacket}). */
    public static void handle(ShowDifficultyConfirmPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> DifficultyChangeConfirmClient.onShow(packet.from(), packet.to(), packet.targetEmpty()));
    }
}
