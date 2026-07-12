package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.platform.DtModId;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.ContainerContentsLinkPropagator;
import games.brennan.dungeontrain.editor.ContainerContentsMenuController;
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
import games.brennan.dungeontrain.net.platform.DtNetSender;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;
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
        new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "save_loot_prefab"));

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

    public static void handle(SaveLootPrefabPacket packet, DtPayloadContext ctx) {
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
            // Category is captured from the open-menu state — block hits leave
            // it as "loot" (default), entity hits set it per entity type.
            // Override sourceBlock for entity categories so the creative-tab
            // icon resolves to the entity's item form (armor stand item /
            // item frame item) rather than the empty air at the entity's
            // floored block position.
            String category = ContainerContentsMenuController.categoryFor(player);
            ResourceLocation sourceBlock = switch (category) {
                case LootPrefabStore.CATEGORY_ARMOR_STAND ->
                    ResourceLocation.fromNamespaceAndPath("minecraft", "armor_stand");
                case LootPrefabStore.CATEGORY_ITEM_FRAME ->
                    ResourceLocation.fromNamespaceAndPath("minecraft", "item_frame");
                default -> BuiltInRegistries.BLOCK.getKey(targetState.getBlock());
            };
            try {
                boolean isNew = LootPrefabStore.save(packet.name(), pool, sourceBlock, category);
                String suffix = writeToSourceTreeIfDevMode(packet.name(), pool, sourceBlock, category);
                // Auto-link: saving as 'foo' declares this container's contents
                // are template 'foo'. Future saves use this name without
                // re-prompting via PrefabNameScreen.
                store.setLink(packet.localPos(), packet.name());
                try {
                    store.save();
                } catch (IOException linkErr) {
                    LOGGER.warn("[DungeonTrain] Could not persist container link for {}: {}",
                        plot.key(), linkErr.toString());
                }
                actionBar(player,
                    (isNew ? "Saved loot '" : "Overwrote loot '") + packet.name() + "' (linked)" + suffix,
                    ChatFormatting.GREEN);
                broadcastSync();
                // Propagate to all linked containers across all editor plots
                // so every chest tied to this template re-rolls with the new
                // pool. Cheap — bounded by the number of linked containers.
                ContainerContentsLinkPropagator.propagate(level, packet.name());
                ContainerContentsMenuController.resyncIfOpen(player, plot.key(), packet.localPos());
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] SaveLootPrefab failed: {}", e.toString());
                actionBar(player, "Save failed: " + e.getClass().getSimpleName(),
                    ChatFormatting.RED);
            }
        });
    }

    private static String writeToSourceTreeIfDevMode(String name, ContainerContentsPool pool,
                                                      ResourceLocation sourceBlock, String category) {
        if (!EditorDevMode.isEnabled()) return "";
        if (!LootPrefabStore.sourceTreeAvailable()) return " (no src tree)";
        try {
            LootPrefabStore.saveToSource(name, pool, sourceBlock, category);
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
        DtNetSender.get().sendToAllPlayers(PrefabRegistrySyncPacket.fromRegistries());
    }
}
