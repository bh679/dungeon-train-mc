package games.brennan.dungeontrain.train.id;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-world record of what each carriage group was built with ({@link GroupRecord}), keyed by the
 * group's anchor carriage index. This is what lets the short id {@code QXRT 4821-K} stand for a full
 * one inside its own world: the inputs it leaves out are looked up here.
 *
 * <p>Written by {@code TrainAssembler} at the one point the group's gate is resolved, so it is what
 * was actually built. A group spawned again at the same anchor (the appender re-spawning a ghost
 * anchor, or a later run on the same seed) overwrites its entry: the newest build is the one standing.
 * Roughly 40 bytes a group.</p>
 */
public final class CarriageIdLedger extends SavedData {

    private static final String NAME = "dungeontrain_carriage_ids";
    private static final String TAG_GROUPS = "groups";

    private final Map<Integer, GroupRecord> byAnchor = new HashMap<>();

    public static CarriageIdLedger get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(CarriageIdLedger::new, (tag, registries) -> load(tag)), NAME);
    }

    /** The record for the group anchored at {@code anchorPIdx}, or {@code null} if none was kept. */
    public synchronized GroupRecord get(int anchorPIdx) {
        return byAnchor.get(anchorPIdx);
    }

    public synchronized void put(int anchorPIdx, GroupRecord record) {
        GroupRecord prev = byAnchor.put(anchorPIdx, record);
        if (!record.equals(prev)) setDirty();
    }

    public synchronized int size() {
        return byAnchor.size();
    }

    static CarriageIdLedger load(CompoundTag tag) {
        CarriageIdLedger ledger = new CarriageIdLedger();
        ListTag groups = tag.getList(TAG_GROUPS, Tag.TAG_INT_ARRAY);
        for (int i = 0; i < groups.size(); i++) {
            int[] entry = groups.getIntArray(i);
            if (entry.length < 2) continue;
            GroupRecord record = GroupRecord.fromInts(java.util.Arrays.copyOfRange(entry, 1, entry.length));
            if (record != null) ledger.byAnchor.put(entry[0], record);
        }
        return ledger;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag groups = new ListTag();
        for (Map.Entry<Integer, GroupRecord> e : byAnchor.entrySet()) {
            int[] body = e.getValue().toInts();
            int[] entry = new int[body.length + 1];
            entry[0] = e.getKey();
            System.arraycopy(body, 0, entry, 1, body.length);
            groups.add(new IntArrayTag(entry));
        }
        tag.put(TAG_GROUPS, groups);
        return tag;
    }
}
