package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.LootPrefabStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * C→S: save the loot pool at {@code localPos} in the player's current
 * editor plot as a named loot prefab. Counterpart to
 * {@link SaveBlockVariantPrefabPacket} for the C-key menu's Save button.
 */
public record SaveLootPrefabPacket(BlockPos localPos, String name) {

    private static final Logger LOGGER = LogUtils.getLogger();

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(localPos);
        buf.writeUtf(name, 64);
    }

    public static SaveLootPrefabPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String n = buf.readUtf(64);
        return new SaveLootPrefabPacket(pos, n);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            if (!player.hasPermissions(2)) {
                actionBar(player, "Save requires OP", ChatFormatting.RED);
                return;
            }
            if (!LootPrefabStore.isValidName(name)) {
                actionBar(player, "Invalid name '" + name + "' (a-z, 0-9, _, 1-32 chars)",
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
            ContainerContentsPool pool = store.poolAt(localPos);
            if (pool.isEmpty()) {
                actionBar(player, "No loot at this container", ChatFormatting.YELLOW);
                return;
            }
            // Capture the container block at this cell so the prefab can
            // place the matching block (chest / barrel / dispenser) when
            // selected from the creative tab. Falls back to chest if the
            // block isn't a registered container.
            BlockPos worldPos = plot.origin().offset(localPos);
            BlockState targetState = level.getBlockState(worldPos);
            ResourceLocation sourceBlock = BuiltInRegistries.BLOCK.getKey(targetState.getBlock());
            try {
                boolean isNew = LootPrefabStore.save(name, pool, sourceBlock);
                actionBar(player,
                    (isNew ? "Saved loot '" : "Overwrote loot '") + name + "'",
                    ChatFormatting.GREEN);
                broadcastSync();
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] SaveLootPrefab failed: {}", e.toString());
                actionBar(player, "Save failed: " + e.getClass().getSimpleName(),
                    ChatFormatting.RED);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void actionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(Component.literal(text).withStyle(colour), true);
    }

    private static void broadcastSync() {
        PrefabRegistrySyncPacket pkt = PrefabRegistrySyncPacket.fromRegistries();
        DungeonTrainNet.CHANNEL.send(PacketDistributor.ALL.noArg(), pkt);
    }
}
