package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import games.brennan.dungeontrain.editor.CarriageVariantContentsAllowStore;
import games.brennan.dungeontrain.editor.PortalRoomContentsAllowStore;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsAllowList;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Client → server: "which contents templates may spawn in this carriage / portal room?" Answered with
 * a {@link ContentsAllowSyncPacket} for the Contents toggle screen. Operators only, like the toggle
 * commands the screen dispatches.
 *
 * <p>The answer is the <b>effective</b> state, not the sidecar: an opt-in template with no explicit
 * decision reads as off. The client cannot work that out itself — it holds neither the sidecar nor
 * the opt-in marks.</p>
 */
public record ContentsAllowRequestPacket(String kind, String target) implements CustomPacketPayload {

    /** {@link #kind} for a carriage variant's shell. */
    public static final String KIND_CARRIAGE = "carriage";
    /** {@link #kind} for a portal room. */
    public static final String KIND_PORTAL_ROOM = "portal_room";

    public static final Type<ContentsAllowRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "contents_allow_request"));

    public static final StreamCodec<FriendlyByteBuf, ContentsAllowRequestPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> {
                buf.writeUtf(p.kind(), ContentsAllowSyncPacket.NAME_MAX);
                buf.writeUtf(p.target(), ContentsAllowSyncPacket.NAME_MAX);
            },
            buf -> new ContentsAllowRequestPacket(buf.readUtf(ContentsAllowSyncPacket.NAME_MAX),
                buf.readUtf(ContentsAllowSyncPacket.NAME_MAX)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ContentsAllowRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || !player.hasPermissions(2)) return;
            DungeonTrainNet.sendTo(player, build(packet.kind(), packet.target()));
        });
    }

    /**
     * The effective allow state of every top-level contents for {@code target} — also pushed after
     * each toggle. An unknown carriage answers with no explicit decisions, the same as a carriage with
     * no sidecar.
     */
    public static ContentsAllowSyncPacket build(String kind, String target) {
        CarriageContentsAllowList allow = allowFor(kind, target);
        // Top-level only: group members resolve through their parent and never meet the allow-list.
        Set<String> children = CarriageContentsGroupStore.allChildIds();
        List<String> rows = new ArrayList<>();
        List<String> off = new ArrayList<>();
        // A carriage only ever takes contents of its own size, so only those are worth a toggle.
        // A portal room fits whatever is small enough, so it keeps the whole list.
        games.brennan.dungeontrain.train.ContentsSize shellSize = KIND_PORTAL_ROOM.equals(kind) ? null
            : games.brennan.dungeontrain.train.CarriagePlacer.sizeOfId(target.toLowerCase(Locale.ROOT));
        for (CarriageContents c : CarriageContentsRegistry.allContents()) {
            String id = c.id();
            if (children.contains(id)) continue;
            if (shellSize != null
                    && games.brennan.dungeontrain.train.CarriageContentsPlacer.sizeOf(id) != shellSize) continue;
            rows.add(id);
            if (!allow.isAllowed(id)) off.add(id);
        }
        return new ContentsAllowSyncPacket(kind, target, rows, off);
    }

    private static CarriageContentsAllowList allowFor(String kind, String target) {
        if (KIND_PORTAL_ROOM.equals(kind)) return PortalRoomContentsAllowStore.getOrEmpty(target);
        return CarriageVariantRegistry.find(target.toLowerCase(Locale.ROOT))
            .flatMap(CarriageVariantContentsAllowStore::get)
            .orElse(CarriageContentsAllowList.EMPTY);
    }
}
