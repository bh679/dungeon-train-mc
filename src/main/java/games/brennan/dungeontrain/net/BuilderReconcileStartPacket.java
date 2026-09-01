package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayReconcile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: put my missing builds back.
 *
 * <p>Carries the player's answer to the second tier and nothing else — no list of builds. The server
 * re-scans and works from what it finds, so the set restored is the set that is actually missing at
 * the moment the button is pressed, not a list a client could name. A client that could name builds
 * could name somebody else's.</p>
 *
 * @param includeBackups also restore builds whose only copy is in a backup archive
 */
public record BuilderReconcileStartPacket(boolean includeBackups) implements CustomPacketPayload {

    public static final Type<BuilderReconcileStartPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_reconcile_start"));

    public static final StreamCodec<FriendlyByteBuf, BuilderReconcileStartPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeBoolean(packet.includeBackups),
            buf -> new BuilderReconcileStartPacket(buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderReconcileStartPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            // The overworld's saved data holds this install's relay records — the same level every
            // other builder-profile packet reads.
            ServerLevel level = server.overworld();
            start(player, level, packet.includeBackups);
        });
    }

    /**
     * Scan, restore, and tell the player what happened. Shared with {@code /dtrebuild}.
     *
     * @param includeBackups whether the second tier — builds with no file left on disk — is included
     */
    public static void start(ServerPlayer player, ServerLevel level, boolean includeBackups) {
        BuilderRelayReconcile.scan(player, level).thenAccept(scan -> {
            if (!scan.reachable()) {
                tell(player, "gui.dungeontrain.builder.reconcile.unreachable", ChatFormatting.YELLOW);
                return;
            }
            if (scan.isEmpty()) {
                tell(player, "gui.dungeontrain.builder.reconcile.nothing", ChatFormatting.GRAY);
                return;
            }
            int queued = includeBackups ? scan.total() : scan.onDisk().size();
            if (queued == 0) {
                tell(player, "gui.dungeontrain.builder.reconcile.nothing", ChatFormatting.GRAY);
                return;
            }
            tell(player, "gui.dungeontrain.builder.reconcile.started", ChatFormatting.GRAY, queued);
            BuilderRelayReconcile.restore(player, level, scan, includeBackups).thenAccept(outcome -> {
                if (outcome.remaining() > 0) {
                    tell(player, "gui.dungeontrain.builder.reconcile.partial", ChatFormatting.YELLOW,
                            outcome.restored(), outcome.remaining());
                } else if (outcome.failed() > 0) {
                    tell(player, "gui.dungeontrain.builder.reconcile.some_failed", ChatFormatting.YELLOW,
                            outcome.restored(), outcome.failed());
                } else {
                    tell(player, "gui.dungeontrain.builder.reconcile.done", ChatFormatting.GREEN,
                            outcome.restored());
                }
            });
        });
    }

    private static void tell(ServerPlayer player, String key, ChatFormatting colour, Object... args) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        server.execute(() -> {
            if (player.hasDisconnected()) return;
            player.sendSystemMessage(Component.translatable(key, args).withStyle(colour));
        });
    }
}
