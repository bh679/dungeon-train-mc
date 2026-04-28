package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.ContainerContentsEditPacket;
import games.brennan.dungeontrain.net.ContainerContentsSyncPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side driver for the container-contents world-space menu (C key).
 *
 * <p>Mirrors {@link BlockVariantMenuController} with these differences:
 * <ul>
 *   <li>Targets a {@link net.minecraft.world.Container} BlockEntity, not
 *       any block-variant cell.</li>
 *   <li>Pool entries are weighted Items, not BlockStates.</li>
 *   <li>No rotation, no lock-IDs, no clipboard copy.</li>
 *   <li>Storage is a separate per-plot file (see
 *       {@link ContainerContentsStore}) so existing variant sidecars stay
 *       untouched.</li>
 * </ul>
 */
public final class ContainerContentsMenuController {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double TOGGLE_REACH = 8.0;

    private record OpenMenu(String plotKey, BlockPos localPos, Direction face, Vec3 up) {}

    private static final Map<UUID, OpenMenu> OPEN = new ConcurrentHashMap<>();

    private ContainerContentsMenuController() {}

    public static void forget(ServerPlayer player) {
        OPEN.remove(player.getUUID());
    }

    public static void clearAll() {
        OPEN.clear();
    }

    public static void toggle(ServerPlayer player, boolean open) {
        if (!open) {
            OPEN.remove(player.getUUID());
            DungeonTrainNet.sendTo(player, ContainerContentsSyncPacket.empty());
            return;
        }
        if (!player.hasPermissions(2)) {
            actionBar(player, "Container contents menu requires OP", ChatFormatting.RED);
            return;
        }

        ServerLevel level = player.serverLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            actionBar(player, "Not in an editor plot", ChatFormatting.YELLOW);
            return;
        }

        HitResult hit = player.pick(TOGGLE_REACH, 1.0f, false);
        if (!(hit instanceof BlockHitResult bhit) || bhit.getType() == HitResult.Type.MISS) {
            actionBar(player, "Look at a chest, barrel, or other container", ChatFormatting.YELLOW);
            return;
        }
        BlockPos worldPos = bhit.getBlockPos();
        BlockState targetState = level.getBlockState(worldPos);
        if (!ContainerContentsRoller.isContainerState(targetState)) {
            actionBar(player, "Block has no inventory — look at a chest, barrel, dispenser, etc.",
                ChatFormatting.YELLOW);
            return;
        }

        BlockPos localPos = worldPos.subtract(plot.origin());
        if (!plot.inBoundsTolerant(localPos)) {
            actionBar(player, "Container is outside the editor plot", ChatFormatting.YELLOW);
            return;
        }
        BlockPos clampedLocal = clampToFootprint(localPos, plot);
        BlockPos clampedWorld = plot.origin().offset(clampedLocal);

        Direction face = bhit.getDirection();
        Vec3 up = computeUp(face, player);
        OPEN.put(player.getUUID(), new OpenMenu(plot.key(), clampedLocal, face, up));
        sendSync(player, plot, clampedLocal, clampedWorld, face, up);
    }

    private static Vec3 computeUp(Direction face, Player player) {
        if (face.getAxis() != Direction.Axis.Y) return new Vec3(0, 1, 0);
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0, look.z);
        if (horizontal.lengthSqr() < 1.0e-6) horizontal = new Vec3(0, 0, 1);
        return horizontal.normalize();
    }

    private static BlockPos clampToFootprint(BlockPos localPos, BlockVariantPlot plot) {
        net.minecraft.core.Vec3i f = plot.footprint();
        int x = Math.max(0, Math.min(f.getX() - 1, localPos.getX()));
        int y = Math.max(0, Math.min(f.getY() - 1, localPos.getY()));
        int z = Math.max(0, Math.min(f.getZ() - 1, localPos.getZ()));
        return new BlockPos(x, y, z);
    }

    private static void sendSync(ServerPlayer player, BlockVariantPlot plot,
                                 BlockPos localPos, BlockPos worldPos,
                                 Direction face, Vec3 up) {
        ContainerContentsStore store = ContainerContentsStore.loadFor(plot.key());
        ContainerContentsPool pool = store.poolAt(localPos);
        BlockState state = player.serverLevel().getBlockState(worldPos);
        int containerSize = ContainerContentsRoller.slotsForContainer(state);
        DungeonTrainNet.sendTo(player,
            buildSyncPacket(plot, localPos, worldPos, face, up, pool, containerSize));
    }

    private static ContainerContentsSyncPacket buildSyncPacket(
        BlockVariantPlot plot, BlockPos localPos, BlockPos worldPos,
        Direction face, Vec3 up, ContainerContentsPool pool, int containerSize
    ) {
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 faceCentre = new Vec3(
            worldPos.getX() + 0.5 + face.getStepX() * 0.5,
            worldPos.getY() + 0.5 + face.getStepY() * 0.5,
            worldPos.getZ() + 0.5 + face.getStepZ() * 0.5);
        Vec3 anchor = faceCentre.add(normal.scale(0.02));
        Vec3 right = up.cross(normal).normalize();

        List<ContainerContentsSyncPacket.Entry> entries = new ArrayList<>(pool.entries().size());
        for (ContainerContentsEntry e : pool.entries()) {
            entries.add(new ContainerContentsSyncPacket.Entry(
                e.itemId().toString(), e.count(), e.weight()));
        }
        return new ContainerContentsSyncPacket(plot.key(), localPos, entries,
            pool.fillMin(), pool.fillMax(), containerSize, anchor, right, up);
    }

    public static void applyEdit(ServerPlayer player, ContainerContentsEditPacket packet) {
        if (!player.hasPermissions(2)) {
            LOGGER.warn("[DungeonTrain] ContainerContentsMenu edit rejected: player {} not OP",
                player.getName().getString());
            return;
        }
        ServerLevel level = player.serverLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null || !plot.key().equals(packet.plotKey())) {
            LOGGER.warn("[DungeonTrain] ContainerContentsMenu edit rejected: player {} not in plot for '{}'",
                player.getName().getString(), packet.plotKey());
            return;
        }
        BlockPos localPos = packet.localPos();
        if (!plot.inBounds(localPos)) {
            LOGGER.warn("[DungeonTrain] ContainerContentsMenu edit rejected: localPos {} out of bounds",
                localPos);
            return;
        }

        ContainerContentsStore store = ContainerContentsStore.loadFor(plot.key());
        ContainerContentsPool current = store.poolAt(localPos);
        ContainerContentsPool next = current;
        boolean dirty = false;

        switch (packet.op()) {
            case ADD -> {
                ResourceLocation id;
                int count;
                if (packet.itemId() != null && !packet.itemId().isEmpty()) {
                    id = ResourceLocation.tryParse(packet.itemId());
                    if (id == null) {
                        actionBar(player, "Bad item id: " + packet.itemId(), ChatFormatting.YELLOW);
                        return;
                    }
                    count = 1;
                } else {
                    // Empty itemId → use main-hand item.
                    ItemStack held = player.getMainHandItem();
                    if (held.isEmpty() || held.getItem() == Items.AIR) {
                        actionBar(player, "Hold an item or use the search screen to add",
                            ChatFormatting.YELLOW);
                        return;
                    }
                    Item item = held.getItem();
                    id = BuiltInRegistries.ITEM.getKey(item);
                    count = held.getCount();
                }
                if (current.size() >= ContainerContentsPool.MAX_ENTRIES) {
                    actionBar(player, "Pool full (max " + ContainerContentsPool.MAX_ENTRIES + ")",
                        ChatFormatting.YELLOW);
                    return;
                }
                next = current.added(new ContainerContentsEntry(id, count, 1));
                dirty = true;
            }
            case REMOVE -> {
                if (current.isEmpty()) return;
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                next = current.removed(idx);
                dirty = true;
            }
            case CLEAR -> {
                if (current.isEmpty()) return;
                next = ContainerContentsPool.empty();
                dirty = true;
            }
            case BUMP_WEIGHT -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                int newWeight = Math.max(1, e.weight() + packet.delta());
                next = current.replaced(idx, e.withWeight(newWeight));
                dirty = true;
            }
            case BUMP_COUNT -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                int newCount = Math.max(1, Math.min(64, e.count() + packet.delta()));
                next = current.replaced(idx, e.withCount(newCount));
                dirty = true;
            }
            case BUMP_FILL_MIN -> {
                // Linear bump in [0, MAX_FILL_BOUND]. Clamped to ≤ fillMax
                // (treating FILL_ALL as +∞).
                int cur = current.fillMin();
                int next0 = Math.max(0, Math.min(ContainerContentsPool.MAX_FILL_BOUND, cur + packet.delta()));
                if (current.fillMax() != ContainerContentsPool.FILL_ALL && next0 > current.fillMax()) {
                    next0 = current.fillMax();
                }
                next = current.withFillMin(next0);
                dirty = true;
            }
            case BUMP_FILL_MAX -> {
                // Cycle through FILL_ALL → 0 → 1 … → MAX → FILL_ALL.
                int cur = current.fillMax();
                int next0;
                if (packet.delta() > 0) {
                    next0 = cur == ContainerContentsPool.FILL_ALL ? 0 : cur + 1;
                    if (next0 > ContainerContentsPool.MAX_FILL_BOUND) next0 = ContainerContentsPool.FILL_ALL;
                } else {
                    next0 = cur == ContainerContentsPool.FILL_ALL
                        ? ContainerContentsPool.MAX_FILL_BOUND
                        : (cur == 0 ? ContainerContentsPool.FILL_ALL : cur - 1);
                }
                // Bring fillMin down if it would exceed the new ceiling.
                int newMin = current.fillMin();
                if (next0 != ContainerContentsPool.FILL_ALL && newMin > next0) newMin = next0;
                next = current.withFillMax(next0).withFillMin(newMin);
                dirty = true;
            }
        }

        if (!dirty) return;
        store.putPool(localPos, next);
        try {
            store.save();
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] ContainerContentsStore save failed for {}: {}",
                plot.key(), e.toString());
            actionBar(player, "Save failed: " + e.getClass().getSimpleName(), ChatFormatting.RED);
        }

        if (next.isEmpty() && packet.op() == ContainerContentsEditPacket.Op.CLEAR) {
            OPEN.remove(player.getUUID());
            DungeonTrainNet.sendTo(player, ContainerContentsSyncPacket.empty());
            return;
        }
        resyncSameFace(player, plot, localPos);
    }

    private static void resyncSameFace(ServerPlayer player, BlockVariantPlot plot, BlockPos localPos) {
        OpenMenu open = OPEN.get(player.getUUID());
        Direction face;
        Vec3 up;
        if (open != null
            && open.plotKey().equals(plot.key())
            && open.localPos().equals(localPos)) {
            face = open.face();
            up = open.up();
        } else {
            face = Direction.UP;
            up = computeUp(face, player);
        }
        BlockPos worldPos = plot.origin().offset(localPos);
        sendSync(player, plot, localPos, worldPos, face, up);
    }

    private static void actionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(Component.literal(text).withStyle(colour), true);
    }
}
