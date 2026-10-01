package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.BlockVariantPrefabStore;
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

/**
 * C→S: save the variant snippet at {@code localPos} in the player's current
 * editor plot as a named block-variant prefab. Sent when the user types a
 * name in {@code PrefabNameScreen} (opened by the V-key menu's Save button).
 *
 * <p>Server-side flow:
 * <ol>
 *   <li>Validate OP, valid name, plot resolution.</li>
 *   <li>Read the cell's states via {@link BlockVariantPlot#statesAt}.</li>
 *   <li>Reject if no states or fewer than the minimum.</li>
 *   <li>{@link BlockVariantPrefabStore#save} (overwrites if id exists).</li>
 *   <li>Broadcast a fresh {@link PrefabRegistrySyncPacket} so connected
 *       clients see the new id in their creative tab immediately.</li>
 *   <li>Send action-bar feedback to the saver.</li>
 * </ol></p>
 */
public record SaveBlockVariantPrefabPacket(BlockPos localPos, String name) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<SaveBlockVariantPrefabPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "save_block_variant_prefab"));

    public static final StreamCodec<FriendlyByteBuf, SaveBlockVariantPrefabPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            SaveBlockVariantPrefabPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(localPos);
        buf.writeUtf(name, 64);
    }

    public static SaveBlockVariantPrefabPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String n = buf.readUtf(64);
        return new SaveBlockVariantPrefabPacket(pos, n);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SaveBlockVariantPrefabPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (!(p instanceof ServerPlayer player)) return;
            if (!player.hasPermissions(2)) {
                actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.common.save_requires_op"), ChatFormatting.RED);
                return;
            }
            if (!BlockVariantPrefabStore.isValidName(packet.name())) {
                actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.common.invalid_prefab_name", packet.name()),
                    ChatFormatting.RED);
                return;
            }
            ServerLevel level = player.serverLevel();
            CarriageDims dims = DungeonTrainWorldData.get(level).dims();
            BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
            if (plot == null) {
                actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.common.not_in_plot"), ChatFormatting.YELLOW);
                return;
            }
            List<VariantState> states = plot.statesAt(packet.localPos());
            if (states == null || states.isEmpty()) {
                actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.prefab.no_data_at_cell"), ChatFormatting.YELLOW);
                return;
            }
            try {
                boolean isNew = BlockVariantPrefabStore.save(packet.name(), states);
                String suffix = writeToSourceTreeIfDevMode(packet.name(), states);
                actionBar(player,
                    Component.translatable(isNew ? "chat.dungeontrain.editor_bar.prefab.saved_prefab" : "chat.dungeontrain.editor_bar.prefab.overwrote_prefab",
                        packet.name(), suffix),
                    ChatFormatting.GREEN);
                broadcastSync();
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] SaveBlockVariantPrefab failed: {}", e.toString());
                actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.common.save_failed", e.getClass().getSimpleName()),
                    ChatFormatting.RED);
            }
        });
    }

    private static String writeToSourceTreeIfDevMode(String name, List<VariantState> states) {
        if (!EditorDevMode.isEnabled()) return "";
        if (!BlockVariantPrefabStore.sourceTreeAvailable()) return " (no src tree)";
        try {
            BlockVariantPrefabStore.saveToSource(name, states);
            return " (→ src)";
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] SaveBlockVariantPrefab source write failed: {}", e.toString());
            return " (src write failed)";
        }
    }

    private static void actionBar(ServerPlayer player, Component text, ChatFormatting colour) {
        player.displayClientMessage(text.copy().withStyle(colour), true);
    }

    private static void broadcastSync() {
        PacketDistributor.sendToAllPlayers(PrefabRegistrySyncPacket.fromRegistries());
    }
}
