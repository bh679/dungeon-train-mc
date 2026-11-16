package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.NarrativeReportReporter;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.narrative.NarrativeProgressData;
import games.brennan.dungeontrain.narrative.PlayerNarrativeBookTag;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player reported the player-written letter on the lectern at {@code pos} — the ⚠
 * control on the lectern screen ({@code LecternReportClientEvents}). A report on one letter is a report
 * on its whole SERIES.
 *
 * <p>The lectern sibling of {@link BookReportPacket}, which validates against the HELD stack. A lectern
 * book is not held, so this carries only the position and the server reads everything else itself: the
 * player must be within reach of {@code pos}, the block entity must be a lectern, and its book must carry
 * {@link PlayerNarrativeBookTag}. The series id comes from the server's own stack, so a modified client
 * can name no series it is not standing at, and dev-authored stories (which carry the other tag) can never
 * be reported.</p>
 *
 * <p>On a match it always records the report in the world ({@link NarrativeProgressData#markSeriesReported})
 * — the series then reads as reported on every lectern for this player and the world stops serving it —
 * and re-stamps the lectern's book so the open screen updates. Only then, gated on network consent like
 * every reporter, does {@link NarrativeReportReporter} queue the relay POST.</p>
 */
public record LecternReportPacket(BlockPos pos) implements CustomPacketPayload {

    /** Reach slack beyond the player's block interaction range, same as vanilla's menu still-valid check. */
    private static final double REACH_SLACK = 1.0;

    public static final Type<LecternReportPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "lectern_report"));

    public static final StreamCodec<FriendlyByteBuf, LecternReportPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeBlockPos(p.pos), buf -> new LecternReportPacket(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LecternReportPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (packet.pos == null || !(player.level() instanceof ServerLevel level)) return;
            if (!level.isLoaded(packet.pos) || !player.canInteractWithBlock(packet.pos, REACH_SLACK)) return;
            if (!(level.getBlockEntity(packet.pos) instanceof LecternBlockEntity lectern)) return;

            ItemStack book = lectern.getBook();
            PlayerNarrativeBookTag.read(book).ifPresent(pid -> {
                NarrativeProgressData data = NarrativeProgressData.get(level.getServer().overworld());
                boolean fresh = data.markSeriesReported(pid.seriesId(), player.getUUID());
                PlayerNarrativeBookTag.setReported(book, true);
                lectern.setChanged();
                // A repeat (two lecterns of a series, or a re-sent click) changes nothing and posts nothing;
                // the relay dedupes per (series, player) anyway.
                if (!fresh || !NetworkConsentMirror.isGranted(player)) return;
                NarrativeReportReporter.report(player.getUUID(), player.getName().getString(),
                    pid.seriesId(), pid.letterIndex());
            });
        });
    }
}
