package games.brennan.dungeontrain.compat.vista;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import net.mehvahdjukaar.vista.common.connection.ConnectionType;
import net.mehvahdjukaar.vista.common.connection.IConnectedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Joins the Vista TVs a contents stamp just wrote into whole screens, the way placing them by hand
 * would.
 *
 * <p>Vista joins TVs only in {@code TVBlock#setPlacedBy}, which a template stamp never calls. The
 * stamp copies each cell's saved {@code connection} state verbatim instead, and that breaks as soon
 * as the contents are flipped: the states are relative to the TV's facing, so a left ↔ right mirror
 * moves the cells but leaves their LEFT/RIGHT edges pointing the old way, and the master cell (the
 * bottom-left one, which holds the screen's block entity) no longer sits in the corner the others walk
 * to. TVs written one cell at a time by a variant or pool roll never joined at all.</p>
 *
 * <p>So after the whole carriage is stamped, every TV in the box is reset to a lone screen and grown
 * again with Vista's own {@link IConnectedBlock#enlargeConnection} — the same call a placement makes,
 * with the same size and aspect limits. Each old screen's block-entity data (cassette, power, speaker)
 * is carried over to the master of the screen it ends up in, so a re-join never empties a TV.</p>
 */
public final class TvTemplateJoin {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The screen size Vista keeps in the master's block entity — rewritten by the join, never carried. */
    static final Set<String> SIZE_KEYS = Set.of("ConnectionWidth", "ConnectionHeight");

    /** Bottom row first, then a fixed order within a row, so the join is the same on every stamp. */
    static final Comparator<BlockPos> SCAN_ORDER = Comparator.<BlockPos>comparingInt(p -> p.getY())
        .thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ());

    private TvTemplateJoin() {}

    /** Re-joins every Vista TV inside the box {@code origin} .. {@code origin + size - 1}. */
    public static void rejoin(ServerLevel level, BlockPos origin, Vec3i size) {
        List<BlockPos> tvs = findTvs(level, origin, size);
        if (tvs.isEmpty()) return;
        try {
            Map<BlockPos, CompoundTag> carried = captureScreenData(level, tvs);
            for (BlockPos pos : tvs) resetToSingle(level, pos);
            for (BlockPos pos : tvs) grow(level, pos);
            restoreScreenData(level, carried);
        } catch (RuntimeException e) {
            // A TV left as separate screens is cosmetic; a failed carriage stamp is not.
            LOGGER.warn("[DungeonTrain] TV re-join failed at {} ({} TVs); leaving them as stamped", origin, tvs.size(), e);
        }
    }

    static List<BlockPos> findTvs(ServerLevel level, BlockPos origin, Vec3i size) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < size.getY(); y++) {
            for (int x = 0; x < size.getX(); x++) {
                for (int z = 0; z < size.getZ(); z++) {
                    cursor.setWithOffset(origin, x, y, z);
                    if (level.getBlockState(cursor).getBlock() instanceof IConnectedBlock) {
                        found.add(cursor.immutable());
                    }
                }
            }
        }
        found.sort(SCAN_ORDER);
        return found;
    }

    /** Each TV block entity's data, minus the screen size, keyed by where it stood before the join. */
    private static Map<BlockPos, CompoundTag> captureScreenData(ServerLevel level, List<BlockPos> tvs) {
        Map<BlockPos, CompoundTag> carried = new LinkedHashMap<>();
        for (BlockPos pos : tvs) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) carried.put(pos, withoutSize(be.saveWithoutMetadata(level.registryAccess())));
        }
        return carried;
    }

    static CompoundTag withoutSize(CompoundTag tag) {
        CompoundTag copy = tag.copy();
        SIZE_KEYS.forEach(copy::remove);
        return copy;
    }

    /** Same block, new state: Vista's {@code onRemove} only shrinks a screen when the block changes. */
    private static void resetToSingle(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof IConnectedBlock tv)) return;
        BlockState single = state.setValue(tv.connectionProperty(), ConnectionType.SINGLE);
        if (single != state) level.setBlock(pos, single, CarriageStampGuard.STAMP_FLAGS);
    }

    /** Grows a still-lone TV into the largest screen Vista allows; cells already joined are skipped. */
    private static void grow(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof IConnectedBlock tv)) return;
        if (state.getValue(tv.connectionProperty()) != ConnectionType.SINGLE) return;
        tv.enlargeConnection(state, level, pos);
    }

    /** Gives each new screen's master the data of the first old screen that ended up inside it. */
    private static void restoreScreenData(ServerLevel level, Map<BlockPos, CompoundTag> carried) {
        Set<BlockPos> filled = new HashSet<>();
        for (Map.Entry<BlockPos, CompoundTag> entry : carried.entrySet()) {
            BlockPos oldPos = entry.getKey();
            BlockState state = level.getBlockState(oldPos);
            if (!(state.getBlock() instanceof IConnectedBlock tv)) continue;
            BlockEntity master = tv.findMasterBlockEntity(level, oldPos, state);
            if (master == null || !filled.add(master.getBlockPos())) continue;
            CompoundTag merged = master.saveWithoutMetadata(level.registryAccess());
            merged.merge(entry.getValue());
            master.loadWithComponents(merged, level.registryAccess());
            master.setChanged();
            level.sendBlockUpdated(master.getBlockPos(), master.getBlockState(), master.getBlockState(),
                CarriageStampGuard.STAMP_FLAGS);
        }
    }
}
