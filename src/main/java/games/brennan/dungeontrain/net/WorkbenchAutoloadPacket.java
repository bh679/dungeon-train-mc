package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import games.brennan.dungeontrain.builder.relay.BuilderRelayStage;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorStampedCategoryState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Client → server: the Workbench tab's <b>Autoload</b> — put every listed relay build on the Workbench
 * in one go.
 *
 * <p>The server enters the Workbench for the player first when another category is resident (the same
 * command the X menu's category bar runs), then stages the builds <em>one after another</em> — each is
 * a relay fetch, and staging them in sequence keeps the slots marching along the row beside the player
 * rather than racing for the same free space. Every build answers with its own
 * {@link BuilderProfileDownloadResultPacket}, keyed by relay id, so the screen can count them in.</p>
 */
public record WorkbenchAutoloadPacket(List<Item> items, boolean live) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** As many builds as one press may stage — the relay's pooled listing is capped far above this. */
    public static final int MAX_ITEMS = 64;

    /** One build to stage: its relay row and whose it is, as the listing captioned it. */
    public record Item(int relayId, String ownerUuid, String ownerName) {
        public Item {
            ownerUuid = ownerUuid == null ? "" : ownerUuid;
            ownerName = ownerName == null ? "" : ownerName;
        }
    }

    public WorkbenchAutoloadPacket {
        items = items == null ? List.of() : List.copyOf(items.size() > MAX_ITEMS ? items.subList(0, MAX_ITEMS) : items);
    }

    public static final Type<WorkbenchAutoloadPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "workbench_autoload"));

    public static final StreamCodec<FriendlyByteBuf, WorkbenchAutoloadPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeCollection(packet.items, (b, item) -> {
                    b.writeVarInt(item.relayId());
                    b.writeUtf(item.ownerUuid(), 48);
                    b.writeUtf(item.ownerName(), 64);
                });
                buf.writeBoolean(packet.live);
            },
            buf -> new WorkbenchAutoloadPacket(
                buf.readCollection(size -> new ArrayList<Item>(Math.min(size, MAX_ITEMS)),
                    b -> new Item(b.readVarInt(), b.readUtf(48), b.readUtf(64))),
                buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WorkbenchAutoloadPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null || !player.hasPermissions(2) || packet.items.isEmpty()) return;
            ServerLevel level = server.overworld();
            boolean live = BuilderProfileRequestPacket.liveRequested(packet.live);
            // Into the Workbench first, so the stages below stamp beside the player as they land.
            if (!EditorStampedCategoryState.isActive(EditorCategory.WORKBENCH)) {
                server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "dungeontrain editor workbench");
            }
            LOGGER.info("[DungeonTrain] Workbench: autoloading {} build(s) for {}", packet.items.size(),
                player.getName().getString());
            stageNext(player, level, packet.items, 0, live);
        });
    }

    /** Stage item {@code i}, answer it, then the next — each fetch waits for the one before. */
    private static void stageNext(ServerPlayer player, ServerLevel level, List<Item> items, int i, boolean live) {
        if (i >= items.size() || player.hasDisconnected()) return;
        Item item = items.get(i);
        String owner = BuilderProfileRequestPacket.viewedOwner(player, item.ownerUuid());
        CompletableFuture<BuilderRelayDownload.Result> staged =
            BuilderRelayStage.stage(player, level, item.relayId(), owner, item.ownerName(), live);
        staged.whenComplete((result, error) -> player.getServer().execute(() -> {
            if (player.hasDisconnected()) return;
            BuilderRelayDownload.Result answer = result != null ? result
                : BuilderRelayDownload.Result.of(BuilderRelayDownload.Outcome.FAILED);
            if (error != null) {
                LOGGER.warn("[DungeonTrain] Workbench: autoload of relay id={} failed: {}", item.relayId(), error.toString());
            }
            DungeonTrainNet.sendTo(player, BuilderProfileDownloadResultPacket.of(answer, item.relayId()));
            stageNext(player, level, items, i + 1, live);
        }));
    }
}
