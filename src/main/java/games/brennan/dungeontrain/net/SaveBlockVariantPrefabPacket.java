package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.BlockVariantPrefabStore;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

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
public record SaveBlockVariantPrefabPacket(BlockPos localPos, String name) {

    private static final Logger LOGGER = LogUtils.getLogger();

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(localPos);
        buf.writeUtf(name, 64);
    }

    public static SaveBlockVariantPrefabPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String n = buf.readUtf(64);
        return new SaveBlockVariantPrefabPacket(pos, n);
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
            if (!BlockVariantPrefabStore.isValidName(name)) {
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
            List<VariantState> states = plot.statesAt(localPos);
            if (states == null || states.isEmpty()) {
                actionBar(player, "No data at this cell", ChatFormatting.YELLOW);
                return;
            }
            try {
                boolean isNew = BlockVariantPrefabStore.save(name, states);
                actionBar(player,
                    (isNew ? "Saved prefab '" : "Overwrote prefab '") + name + "'",
                    ChatFormatting.GREEN);
                broadcastSync();
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] SaveBlockVariantPrefab failed: {}", e.toString());
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
