package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.editor.LootPrefabStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * C→S: save the loot pool at {@code localPos} in the player's current
 * editor plot as a named loot prefab. Counterpart to
 * {@link SaveBlockVariantPrefabPacket} for the C-key menu's Save button.
 */
public record SaveLootPrefabPacket(BlockPos localPos, String name) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<SaveLootPrefabPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "save_loot_prefab"));

    public static final StreamCodec<FriendlyByteBuf, SaveLootPrefabPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            SaveLootPrefabPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(localPos);
        buf.writeUtf(name, 64);
    }

    public static SaveLootPrefabPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String n = buf.readUtf(64);
        return new SaveLootPrefabPacket(pos, n);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SaveLootPrefabPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (!(p instanceof ServerPlayer player)) return;
            if (!player.hasPermissions(2)) {
                actionBar(player, "Save requires OP", ChatFormatting.RED);
                return;
            }
            if (!LootPrefabStore.isValidName(packet.name())) {
                actionBar(player, "Invalid name '" + packet.name() + "' (a-z, 0-9, _, 1-32 chars)",
                    ChatFormatting.RED);
                return;
            }
            ServerLevel level = player.serverLevel();
            CarriageDims dims = DungeonTrainWorldData.get(level).dims();
            BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
            if (plot == null) {
                actionBar(player, "Not in an editor plot", ChatFormatting.YELLOW);
                return;
            }
            ContainerContentsStore store = ContainerContentsStore.loadFor(plot.key());
            ContainerContentsPool pool = store.poolAt(packet.localPos());
            if (pool.isEmpty()) {
                actionBar(player, "No loot at this container", ChatFormatting.YELLOW);
                return;
            }
            BlockPos worldPos = plot.origin().offset(packet.localPos());
            BlockState targetState = level.getBlockState(worldPos);
            ResourceLocation sourceBlock = BuiltInRegistries.BLOCK.getKey(targetState.getBlock());
            try {
                boolean isNew = LootPrefabStore.save(packet.name(), pool, sourceBlock);
                String suffix = writeToSourceTreeIfDevMode(packet.name(), pool, sourceBlock);
                actionBar(player,
                    (isNew ? "Saved loot '" : "Overwrote loot '") + packet.name() + "'" + suffix,
                    ChatFormatting.GREEN);
                broadcastSync();
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] SaveLootPrefab failed: {}", e.toString());
                actionBar(player, "Save failed: " + e.getClass().getSimpleName(),
                    ChatFormatting.RED);
            }
        });
    }

    private static String writeToSourceTreeIfDevMode(String name, ContainerContentsPool pool, ResourceLocation sourceBlock) {
        if (!EditorDevMode.isEnabled()) return "";
        if (!LootPrefabStore.sourceTreeAvailable()) return " (no src tree)";
        try {
            LootPrefabStore.saveToSource(name, pool, sourceBlock);
            return " (→ src)";
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] SaveLootPrefab source write failed: {}", e.toString());
            return " (src write failed)";
        }
    }

    private static void actionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(Component.literal(text).withStyle(colour), true);
    }

    private static void broadcastSync() {
        PacketDistributor.sendToAllPlayers(PrefabRegistrySyncPacket.fromRegistries());
    }
}
