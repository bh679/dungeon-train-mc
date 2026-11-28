package games.brennan.dungeontrain.perks;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.item.CosmicAppleItem;
import games.brennan.dungeontrain.registry.ModItems;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Until somebody on the server has taken a Cosmic Apple from a chest (in any life), every new carriage group
 * holds {@link #APPLES_PER_GROUP} of them: the natural drop ({@link CosmicAppleDrops}) counts, and extras top
 * it up. Extras are plain Cosmic Apples whose container is marked ({@link #NBT_KEY}) so they can be taken away
 * again. The flow:
 * <ol>
 *   <li><b>Seeking:</b> groups get extras.</li>
 *   <li><b>Opened:</b> a chest holding an apple is opened. No more extras are placed, and every other marked
 *       container loses its extra (loaded ones now, the rest when they are next opened or broken).</li>
 *   <li><b>Taken:</b> that chest holds fewer apples than when it was opened. Everyone online is marked as having
 *       found their first, for good ({@link PerkProfiles#markFirstAppleFound}).</li>
 *   <li><b>Left behind:</b> the opener is a whole group's length of carriages from that chest. Its extra is
 *       deleted and seeking resumes; marked containers ahead get theirs back when opened.</li>
 * </ol>
 * Extras never go in a shared carriage: taking one out later would read as an author's edit and upload.
 */
public final class FirstAppleBoost {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** While seeking, a group holds this many apples, its natural one included. */
    static final int APPLES_PER_GROUP = 2;
    /** Most extras one group takes, counting what earlier groups with nowhere to put theirs carried on. */
    static final int MAX_EXTRAS = 4;
    /** Container persistent-data key: {@code {Slot, PIdx, Group}} of the extra apple it was given. */
    static final String NBT_KEY = "dt_first_apple";
    private static final String TAG_SLOT = "Slot";
    private static final String TAG_PIDX = "PIdx";
    private static final String TAG_GROUP = "Group";
    /** A pot's loot the extra stood in for, put back when the extra is taken away. */
    private static final String TAG_WAS = "Was";

    private FirstAppleBoost() {}

    /** A container's extra apple: which slot, and the carriage and group size it was placed in. */
    record Marker(int slot, int pIdx, int groupSize, ItemStack was) {
        Marker(int slot, int pIdx, int groupSize) {
            this(slot, pIdx, groupSize, ItemStack.EMPTY);
        }
    }

    /**
     * A box of a group that ran a contents pass and is not shared: where extras may go. {@code pIdx} is its
     * first carriage; a chest's own carriage is read from its x ({@code carriageLength} blocks each).
     */
    public record Box(BoundingBox box, int pIdx, int carriageLength) {
        int pIdxAt(BlockPos pos) {
            return pIdx + Math.max(0, (pos.getX() - box.minX()) / Math.max(1, carriageLength));
        }
    }

    /** What a new group gets: extras, empty markers (a chest is open and waiting), or nothing. */
    public enum Owed { EXTRAS, MARKERS, NONE }

    // ---- pure rules ----

    /** Extras a group gets while seeking. */
    static int extrasFor(boolean naturalPlaced) {
        return Math.max(0, APPLES_PER_GROUP - (naturalPlaced ? 1 : 0));
    }

    /** Extras a group is owed: its own, plus what earlier groups carried on, up to {@link #MAX_EXTRAS}. */
    static int owedWith(boolean naturalPlaced, int carried) {
        return Math.min(MAX_EXTRAS, extrasFor(naturalPlaced) + Math.max(0, carried));
    }

    /** What carries on to the next group: whatever of {@code owed} did not fit. */
    static int carryAfter(int owed, int placed) {
        return Math.max(0, owed - placed);
    }

    /** True once the opener is a whole group's length of carriages away from the chest, either way. */
    static boolean leftBehind(int openerPIdx, int chestPIdx, int groupSize) {
        return Math.abs((long) openerPIdx - chestPIdx) >= Math.max(1, groupSize);
    }

    /** True when the opened chest holds fewer apples than it did when opened. */
    static boolean taken(int applesNow, int applesAtOpen) {
        return applesNow < applesAtOpen;
    }

    // ---- seeking ----

    /**
     * Extras while no chest is waiting and someone online has never taken a Cosmic Apple; while one is
     * waiting, empty markers, so the group can be given its extras if that apple is left behind.
     */
    public static Owed owed(MinecraftServer server) {
        if (server == null || !CosmicAppleDrops.open(server)) return Owed.NONE;
        FirstAppleState state = FirstAppleState.get(server);
        if (!anyUnfound(server, state)) return Owed.NONE;
        return state.pending() == null ? Owed.EXTRAS : Owed.MARKERS;
    }

    private static boolean anyUnfound(MinecraftServer server, FirstAppleState state) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (state.foundHere(player.getUUID())) continue;
            if (!state.profileFound(player.getUUID(), id -> PerkProfiles.firstAppleFound(player))) return true;
        }
        return false;
    }

    /** {@code player} ate a Cosmic Apple: whatever chest it came from, the server has found its first. */
    public static void onEaten(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || !PerksGate.isOn()) return;
        FirstAppleState state = FirstAppleState.get(server);
        if (!anyUnfound(server, state)) return;
        markFound(server, state, player.getUUID());
        LOGGER.info("[DT-FirstApple] {} ate a Cosmic Apple; first find recorded", player.getScoreboardName());
    }

    /**
     * Top the group up to {@link #APPLES_PER_GROUP} apples (plus anything carried on), one extra per container:
     * chests that don't hold the natural one first, then those that do, then empty decorated pots. What
     * doesn't fit carries on to the next group. With {@code markersOnly} the containers are marked but left
     * empty, for {@link #onOpen} to fill if seeking resumes, and nothing carries.
     *
     * @return how many extras (or markers) went in
     */
    public static int placeExtras(ServerLevel level, List<Box> boxes, boolean naturalPlaced, int groupSize,
                                  boolean markersOnly) {
        return placeExtras(level, boxes, naturalPlaced, groupSize, markersOnly, pos -> false);
    }

    private static int placeExtras(ServerLevel level, List<Box> boxes, boolean naturalPlaced, int groupSize,
                                   boolean markersOnly, java.util.function.Predicate<BlockPos> skip) {
        FirstAppleState state = FirstAppleState.get(level.getServer());
        int owed = markersOnly ? extrasFor(naturalPlaced) : owedWith(naturalPlaced, state.carried());
        if (owed <= 0) return 0;
        List<Candidate> fresh = new ArrayList<>();
        List<Candidate> holding = new ArrayList<>();
        List<Candidate> potsEmpty = new ArrayList<>();
        List<Candidate> pots = new ArrayList<>();
        for (Box box : boxes) {
            for (Container c : CosmicAppleDrops.containersIn(level, box.box())) {
                BlockPos pos = ((BlockEntity) c).getBlockPos();
                if (skip.test(pos)) continue;
                (countApples(c) > 0 ? holding : fresh).add(new Candidate(c, box.pIdxAt(pos)));
            }
            for (DecoratedPotBlockEntity pot : CosmicAppleDrops.potsIn(level, box.box())) {
                if (skip.test(pot.getBlockPos()) || CosmicAppleItem.isCosmicApple(pot.getTheItem())) continue;
                (pot.getTheItem().isEmpty() ? potsEmpty : pots).add(new Candidate(pot, box.pIdxAt(pot.getBlockPos())));
            }
        }
        java.util.Random random = new java.util.Random(level.getRandom().nextLong());
        Collections.shuffle(fresh, random);
        Collections.shuffle(potsEmpty, random);
        Collections.shuffle(pots, random);
        List<Candidate> order = new ArrayList<>(fresh);
        order.addAll(holding);
        order.addAll(potsEmpty);
        order.addAll(pots); // pots holding loot last: the apple stands in for it
        int placed = 0;
        for (Candidate candidate : order) {
            if (placed >= owed) break;
            BlockEntity be = (BlockEntity) candidate.container();
            if (marker(be) != null) continue; // one extra per container
            boolean pot = be instanceof DecoratedPotBlockEntity;
            int slot = pot ? 0 : firstEmpty(candidate.container());
            if (slot < 0) continue;
            ItemStack was = pot ? candidate.container().getItem(0).copy() : ItemStack.EMPTY;
            if (!markersOnly) {
                candidate.container().setItem(slot, new ItemStack(ModItems.COSMIC_APPLE.get()));
                candidate.container().setChanged();
            }
            writeMarker(be, new Marker(slot, candidate.pIdx(), groupSize, was));
            state.addMarked(new FirstAppleState.Spot(level.dimension(), be.getBlockPos()));
            placed++;
        }
        if (!markersOnly) state.setCarried(carryAfter(owed, placed));
        LOGGER.info("[DT-FirstApple] group of {} at carriage {}: {} of {} {} placed (carried on: {})", groupSize,
            boxes.isEmpty() ? "?" : boxes.get(0).pIdx(), placed, owed, markersOnly ? "empty markers" : "extras",
            state.carried());
        return placed;
    }

    private record Candidate(Container container, int pIdx) {}

    // ---- open ----

    /** A container at {@code pos} was just opened by {@code player} (before its menu shows). */
    public static void onOpen(ServerLevel level, ServerPlayer player, BlockPos pos) {
        MinecraftServer server = level.getServer();
        List<BlockEntity> halves = halvesAt(level, pos);
        if (halves.isEmpty() || halves.get(0) instanceof DecoratedPotBlockEntity) return; // a pot is broken, not opened
        FirstAppleState state = FirstAppleState.get(server);
        FirstAppleState.Pending pending = state.pending();
        if (pending != null) {
            if (isPendingChest(level, pending, halves)) return;
            for (BlockEntity be : halves) removeExtra(be); // lazily cleared: another chest is open and waiting
            return;
        }
        boolean anyMarker = false;
        int applesBefore = 0;
        for (BlockEntity be : halves) {
            anyMarker |= marker(be) != null;
            applesBefore += countApples((Container) be);
        }
        if (!anyMarker && applesBefore <= 0) return; // an ordinary container: nothing to decide
        boolean active = CosmicAppleDrops.open(server) && anyUnfound(server, state);
        Marker found = null;
        for (BlockEntity be : halves) {
            Marker marker = marker(be);
            if (marker == null) continue;
            if (active) {
                restoreExtra(be, marker);
                if (found == null) found = marker(be);
            } else {
                removeExtra(be);
                forget(level, be, state);
            }
        }
        if (!active) return;
        int apples = 0;
        for (BlockEntity be : halves) apples += countApples((Container) be);
        if (apples <= 0) return;
        int pIdx;
        int groupSize;
        if (found != null) {
            pIdx = found.pIdx();
            groupSize = found.groupSize();
        } else {
            Trains.Carriage group = TrainCarriageAppender.groupAt(level, player);
            Integer idx = group == null ? null : TrainCarriageAppender.carriageIndexAt(level, player, group.provider().dims());
            if (idx == null) return; // an apple chest off the train: not a carriage find
            pIdx = idx;
            groupSize = group.provider().getGroupSize();
        }
        FirstAppleState.Spot spot = new FirstAppleState.Spot(level.dimension(), halves.get(0).getBlockPos());
        state.setPending(new FirstAppleState.Pending(spot, pIdx, groupSize, player.getUUID(), apples));
        LOGGER.info("[DT-FirstApple] {} opened an apple chest at {} (carriage {}); clearing the other extras",
            player.getScoreboardName(), spot.pos(), pIdx);
        purgeOthers(server, state, halves);
    }

    /** Clear the extra from every loaded marked container but {@code keep}; markers stay for a later re-arm. */
    private static void purgeOthers(MinecraftServer server, FirstAppleState state, List<BlockEntity> keep) {
        for (FirstAppleState.Spot spot : state.marked()) {
            if (keep.stream().anyMatch(be -> be.getBlockPos().equals(spot.pos()))) continue;
            ServerLevel level = server.getLevel(spot.dim());
            if (level == null || !level.isLoaded(spot.pos())) continue;
            BlockEntity be = level.getBlockEntity(spot.pos());
            if (be == null || marker(be) == null) {
                state.removeMarked(spot);
                continue;
            }
            removeExtra(be);
        }
    }

    // ---- break ----

    /** A marked container is about to break: an extra that is not up for grabs must not drop. */
    public static void onBreak(ServerLevel level, ServerPlayer breaker, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;
        MinecraftServer server = level.getServer();
        if (marker(be) == null) {
            // A pot holding the natural apple: breaking it is a find too.
            if (be instanceof DecoratedPotBlockEntity pot && CosmicAppleItem.isCosmicApple(pot.getTheItem())) {
                FirstAppleState state = FirstAppleState.get(server);
                if (state.pending() == null && CosmicAppleDrops.open(server) && anyUnfound(server, state)) {
                    markFound(server, state, breaker == null ? null : breaker.getUUID());
                    LOGGER.info("[DT-FirstApple] apple pot at {} broken; first find recorded", pos);
                }
            }
            return;
        }
        FirstAppleState state = FirstAppleState.get(server);
        FirstAppleState.Pending pending = state.pending();
        if (be instanceof DecoratedPotBlockEntity && pending == null
                && CosmicAppleDrops.open(server) && anyUnfound(server, state)) {
            // A pot is found by breaking it: its apple drops, and that is the find.
            restoreExtra(be, marker(be));
            forget(level, be, state);
            if (countApples((Container) be) > 0) {
                markFound(server, state, breaker == null ? null : breaker.getUUID());
                LOGGER.info("[DT-FirstApple] apple pot at {} broken; first find recorded", pos);
            }
            return;
        }
        boolean pendingHere = pending != null && isPendingChest(level, pending, List.of(be));
        if (!pendingHere) removeExtra(be);
        forget(level, be, state);
    }

    // ---- tick ----

    /** About once a second: settle the open chest as taken or left behind. */
    public static void tick(MinecraftServer server) {
        FirstAppleState state = FirstAppleState.get(server);
        FirstAppleState.Pending pending = state.pending();
        if (pending == null) return;
        ServerLevel level = server.getLevel(pending.spot().dim());
        if (level == null || !level.isLoaded(pending.spot().pos())) {
            // The chest's group is gone from the world: nobody can take it now.
            state.setPending(null);
            LOGGER.info("[DT-FirstApple] apple chest at {} unloaded; seeking again", pending.spot().pos());
            return;
        }
        List<BlockEntity> halves = halvesAt(level, pending.spot().pos());
        int apples = 0;
        for (BlockEntity be : halves) apples += countApples((Container) be);
        if (taken(apples, pending.applesAtOpen())) {
            markFound(server, state, pending.opener());
            for (BlockEntity be : halves) forget(level, be, state);
            state.setPending(null);
            LOGGER.info("[DT-FirstApple] apple taken from {}; first find recorded", pending.spot().pos());
            return;
        }
        ServerPlayer opener = server.getPlayerList().getPlayer(pending.opener());
        boolean gone = opener == null || opener.level() != level;
        if (!gone) {
            Trains.Carriage group = TrainCarriageAppender.groupAt(level, opener);
            Integer idx = group == null ? null : TrainCarriageAppender.carriageIndexAt(level, opener, group.provider().dims());
            if (idx == null) return; // off the train for now: wait until they are back on
            gone = leftBehind(idx, pending.pIdx(), pending.groupSize());
        }
        if (!gone) return;
        for (BlockEntity be : halves) {
            removeExtra(be);
            forget(level, be, state);
        }
        state.setPending(null);
        LOGGER.info("[DT-FirstApple] apple at {} left behind; seeking again", pending.spot().pos());
    }

    /** The whole server has found its first: {@code finder} (online or not) and everyone online. */
    private static void markFound(MinecraftServer server, FirstAppleState state, java.util.UUID finder) {
        java.util.Set<java.util.UUID> ids = new java.util.LinkedHashSet<>();
        if (finder != null) ids.add(finder);
        state.setCarried(0);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) ids.add(player.getUUID());
        for (java.util.UUID id : ids) {
            state.addFound(id);
            PerkProfiles.markFirstAppleFound(id);
        }
    }

    /**
     * Another drop is taking this container over: drop the first-apple marker (and forget the spot), so the
     * boost never takes that drop's apple back out. Only for a container not holding the boost's apple.
     */
    static void release(ServerLevel level, BlockEntity be) {
        forget(level, be, FirstAppleState.get(level.getServer()));
    }

    // ---- fill ----

    /**
     * About once a second: when the boost has just come on (a player joined or unlocked their first perk, or
     * the server started), give the groups already built their extras, as if they had been made now.
     */
    public static void backfillIfArmed(MinecraftServer server) {
        FirstAppleState state = FirstAppleState.get(server);
        Owed now = owed(server);
        Owed last = state.lastOwed();
        state.setLastOwed(now);
        if (now != Owed.EXTRAS || last == Owed.EXTRAS || last == Owed.MARKERS) return;
        int groups = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Trains.Carriage group : Trains.allCarriages(level)) {
                if (backfillGroup(level, state, group)) groups++;
            }
        }
        LOGGER.info("[DT-FirstApple] boost on: filled {} groups already built", groups);
    }

    private static boolean backfillGroup(ServerLevel level, FirstAppleState state, Trains.Carriage group) {
        java.util.UUID subLevel = group.ship().subLevelId();
        int anchor = group.provider().getPIdx();
        if (!state.markBackfilled(subLevel + "@" + anchor)) return false;
        int size = Math.max(1, group.provider().getGroupSize());
        games.brennan.dungeontrain.train.CarriageDims dims = group.provider().dims();
        BlockPos origin = group.provider().getShipyardOrigin();
        // The plot starts at the back pad; the carriages start half a pad in (see TrainAssembler.spawnGroup).
        BlockPos start = origin.offset(size > 1 ? games.brennan.dungeontrain.train.CarriagePlacer.halfPadLen(dims) : 0, 0, 0);
        BoundingBox bounds = BoundingBox.fromCorners(start, start.offset(dims.length() * size - 1, dims.height(), dims.width()));
        java.util.function.Predicate<BlockPos> shared = pos ->
            games.brennan.dungeontrain.train.SharedCarriageRegistry.resolve(subLevel, pos.getX(), pos.getY(), pos.getZ()) != null;
        boolean natural = false;
        for (Container c : CosmicAppleDrops.containersIn(level, bounds)) {
            if (marker((BlockEntity) c) != null) return false; // made while the boost was on: already has its extras
            natural |= countApples(c) > 0;
        }
        for (DecoratedPotBlockEntity pot : CosmicAppleDrops.potsIn(level, bounds)) {
            if (marker(pot) != null) return false;
            natural |= CosmicAppleItem.isCosmicApple(pot.getTheItem());
        }
        placeExtras(level, List.of(new Box(bounds, anchor, dims.length())), natural, size, false, shared);
        return true;
    }

    // ---- containers ----

    private static boolean isPendingChest(ServerLevel level, FirstAppleState.Pending pending, List<BlockEntity> halves) {
        if (!pending.spot().dim().equals(level.dimension())) return false;
        for (BlockEntity be : halves) {
            if (be.getBlockPos().equals(pending.spot().pos())) return true;
        }
        for (BlockEntity be : halvesAt(level, pending.spot().pos())) {
            for (BlockEntity h : halves) if (h.getBlockPos().equals(be.getBlockPos())) return true;
        }
        return false;
    }

    /** The container at {@code pos}, with the other half of a double chest. */
    static List<BlockEntity> halvesAt(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof Container)) return List.of();
        BlockState state = be.getBlockState();
        if (be instanceof ChestBlockEntity && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockEntity other = level.getBlockEntity(pos.relative(ChestBlock.getConnectedDirection(state)));
            if (other instanceof ChestBlockEntity) return List.of(be, other);
        }
        return List.of(be);
    }

    private static int countApples(Container c) {
        int n = 0;
        for (int s = 0; s < c.getContainerSize(); s++) {
            ItemStack stack = c.getItem(s);
            if (CosmicAppleItem.isCosmicApple(stack)) n += stack.getCount();
        }
        return n;
    }

    private static int firstEmpty(Container c) {
        for (int s = 0; s < c.getContainerSize(); s++) if (c.getItem(s).isEmpty()) return s;
        return -1;
    }

    /** Take the extra out of its slot, if it is still there. The marker stays. */
    private static void removeExtra(BlockEntity be) {
        Marker marker = marker(be);
        if (marker == null || !(be instanceof Container c)) return;
        if (marker.slot() < 0 || marker.slot() >= c.getContainerSize()) return;
        ItemStack stack = c.getItem(marker.slot());
        if (!stack.is(ModItems.COSMIC_APPLE.get())) return;
        ItemStack left = stack.getCount() > 1 ? stack.copyWithCount(stack.getCount() - 1) : marker.was().copy();
        c.setItem(marker.slot(), left);
        c.setChanged();
    }

    /** Put the extra back (seeking again), moving it to another free slot if its own is taken. */
    private static void restoreExtra(BlockEntity be, Marker marker) {
        if (!(be instanceof Container c)) return;
        if (be instanceof DecoratedPotBlockEntity) {
            ItemStack now = c.getItem(0);
            if (now.is(ModItems.COSMIC_APPLE.get())) return;
            writeMarker(be, new Marker(0, marker.pIdx(), marker.groupSize(), now.copy()));
            c.setItem(0, new ItemStack(ModItems.COSMIC_APPLE.get()));
            c.setChanged();
            return;
        }
        if (marker.slot() >= 0 && marker.slot() < c.getContainerSize()) {
            ItemStack stack = c.getItem(marker.slot());
            if (stack.is(ModItems.COSMIC_APPLE.get())) return;
            if (stack.isEmpty()) {
                c.setItem(marker.slot(), new ItemStack(ModItems.COSMIC_APPLE.get()));
                c.setChanged();
                return;
            }
        }
        int slot = firstEmpty(c);
        if (slot < 0) return;
        c.setItem(slot, new ItemStack(ModItems.COSMIC_APPLE.get()));
        writeMarker(be, new Marker(slot, marker.pIdx(), marker.groupSize()));
        c.setChanged();
    }

    private static void forget(ServerLevel level, BlockEntity be, FirstAppleState state) {
        if (marker(be) != null) {
            be.getPersistentData().remove(NBT_KEY);
            be.setChanged();
        }
        state.removeMarked(new FirstAppleState.Spot(level.dimension(), be.getBlockPos()));
    }

    static Marker marker(BlockEntity be) {
        CompoundTag data = be.getPersistentData();
        if (!data.contains(NBT_KEY, Tag.TAG_COMPOUND)) return null;
        CompoundTag tag = data.getCompound(NBT_KEY);
        ItemStack was = ItemStack.EMPTY;
        if (tag.contains(TAG_WAS, Tag.TAG_COMPOUND) && be.getLevel() != null) {
            was = ItemStack.parseOptional(be.getLevel().registryAccess(), tag.getCompound(TAG_WAS));
        }
        return new Marker(tag.getInt(TAG_SLOT), tag.getInt(TAG_PIDX), tag.getInt(TAG_GROUP), was);
    }

    private static void writeMarker(BlockEntity be, Marker marker) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_SLOT, marker.slot());
        tag.putInt(TAG_PIDX, marker.pIdx());
        tag.putInt(TAG_GROUP, marker.groupSize());
        if (!marker.was().isEmpty() && be.getLevel() != null) {
            tag.put(TAG_WAS, marker.was().save(be.getLevel().registryAccess()));
        }
        be.getPersistentData().put(NBT_KEY, tag);
        be.setChanged();
    }
}
