package games.brennan.dungeontrain.world;

import net.minecraft.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which Nether portals on the ride are paired. A portal trip along the track
 * ({@code event/NetherPortalBandJump}) records the frame it left and the frame it arrived in as
 * partners, so stepping back into either returns the player to the other exactly — the way a
 * vanilla pair behaves — even when one of them stands in a band transition the mapping rules would
 * otherwise never land in.
 *
 * <p>Frames are keyed by their canonical corner ({@link #frameKey}: the min corner of the largest
 * portal rectangle around any of the frame's portal blocks, the same helper vanilla uses in
 * {@code NetherPortalBlock}), so every block of a frame resolves to one key. Overworld-only, stored at
 * {@code data/dungeontrain_nether_portal_links.dat}. A partner whose corner is no longer a portal
 * block is forgotten on lookup ({@link #partnerOf}).</p>
 */
public final class NetherPortalLinks extends SavedData {

    public static final String NAME = "dungeontrain_nether_portal_links";
    private static final String TAG_LINKS = "links";
    private static final String TAG_A = "a";
    private static final String TAG_B = "b";

    /** Rectangle search radius, as {@code NetherPortalBlock} uses for frames. */
    private static final int FRAME_SEARCH = 21;

    private final Map<BlockPos, BlockPos> partners = new HashMap<>();

    NetherPortalLinks() {}

    public static NetherPortalLinks get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(NetherPortalLinks::new, (tag, registries) -> load(tag)),
            NAME
        );
    }

    /**
     * Canonical key of the portal frame containing {@code pos}: the min corner of the largest rectangle
     * of identical portal blocks around it. {@code pos} itself when it is not a portal block.
     */
    public static BlockPos frameKey(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Optional<Direction.Axis> axis = state.getOptionalValue(BlockStateProperties.HORIZONTAL_AXIS);
        if (axis.isEmpty()) return pos.immutable();
        BlockUtil.FoundRectangle frame = BlockUtil.getLargestRectangleAround(
            pos, axis.get(), FRAME_SEARCH, Direction.Axis.Y, FRAME_SEARCH, p -> level.getBlockState(p) == state);
        return frame.minCorner.immutable();
    }

    /** Pair two frame keys, both ways. */
    public synchronized void link(BlockPos a, BlockPos b) {
        partners.put(a.immutable(), b.immutable());
        partners.put(b.immutable(), a.immutable());
        setDirty();
    }

    /** The recorded partner of frame {@code key}, or null. Pure lookup — no world check. */
    @Nullable
    public synchronized BlockPos partner(BlockPos key) {
        return partners.get(key);
    }

    /**
     * The partner of the frame at {@code key} if it still stands: a partner whose corner is loaded and
     * no longer a portal block is unlinked and null is returned. An unloaded partner chunk is trusted.
     */
    @Nullable
    public BlockPos partnerOf(ServerLevel level, BlockPos key) {
        BlockPos partner = partner(key);
        if (partner == null) return null;
        if (level.isLoaded(partner) && !level.getBlockState(partner).hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            unlink(key);
            return null;
        }
        return partner;
    }

    /** Forget {@code key} and whatever it pointed at. */
    public synchronized void unlink(BlockPos key) {
        BlockPos partner = partners.remove(key);
        if (partner != null) partners.remove(partner);
        setDirty();
    }

    synchronized int size() {
        return partners.size();
    }

    static NetherPortalLinks load(CompoundTag tag) {
        NetherPortalLinks data = new NetherPortalLinks();
        if (!tag.contains(TAG_LINKS)) return data;
        ListTag list = tag.getList(TAG_LINKS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            data.partners.put(BlockPos.of(e.getLong(TAG_A)), BlockPos.of(e.getLong(TAG_B)));
        }
        return data;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, BlockPos> e : partners.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong(TAG_A, e.getKey().asLong());
            c.putLong(TAG_B, e.getValue().asLong());
            list.add(c);
        }
        tag.put(TAG_LINKS, list);
        return tag;
    }
}
