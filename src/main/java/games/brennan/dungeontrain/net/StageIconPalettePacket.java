package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: what every stage placeholder resolves to for the editor's <b>effective</b>
 * stage ({@code EditorStageSelection.effective()}) — the feed for the stage-aware item icons
 * ({@code client.render.StagePlaceholderItemRenderer}). Pushed per player from the editor's
 * per-tick snapshot loop whenever the stage-blocks index generation or the selected stage changes;
 * an empty {@code stageId} clears it (editor exit).
 */
public record StageIconPalettePacket(String stageId, List<Entry> entries) implements CustomPacketPayload {

    /** @param repeat the slot only repeats an earlier one (looped list read) — drawn dimmed. */
    public record Entry(String name, String blockId, boolean repeat) {}

    public static final Type<StageIconPalettePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "stage_icon_palette"));

    public static final StreamCodec<FriendlyByteBuf, StageIconPalettePacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), StageIconPalettePacket::decode);

    public static StageIconPalettePacket empty() {
        return new StageIconPalettePacket("", List.of());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(stageId);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUtf(e.name());
            buf.writeUtf(e.blockId());
            buf.writeBoolean(e.repeat());
        }
    }

    public static StageIconPalettePacket decode(FriendlyByteBuf buf) {
        String stageId = buf.readUtf(64);
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) entries.add(new Entry(buf.readUtf(64), buf.readUtf(256), buf.readBoolean()));
        return new StageIconPalettePacket(stageId, List.copyOf(entries));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StageIconPalettePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> games.brennan.dungeontrain.client.menu.ClientStagePalette.apply(packet));
    }
}
