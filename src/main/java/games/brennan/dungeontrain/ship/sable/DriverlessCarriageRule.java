package games.brennan.dungeontrain.ship.sable;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Whether a sub-level Sable just woke is a carriage group left over from a train that no longer
 * exists. The decision half of {@link DriverlessCarriageReaper}; no live game state, so it can be
 * tested on its own.
 *
 * <p><b>A sub-level from another mod must never match.</b> A driver means a live group. Without one,
 * the marker {@link #withMark} wrote at assembly is proof. A group saved before the marker existed
 * can only be recognised by its size, so that test is exact, runs only in a world with a train, and
 * anything it does not recognise is left alone.</p>
 *
 * <p>The size is the plot's, not the world box: it is the one thing a driverless group keeps once it
 * has tumbled off the line. Saved poses are no help, since a group re-culled after falling is stored
 * wherever it landed.</p>
 */
final class DriverlessCarriageRule {

    /** Key in Sable's persisted per-sub-level {@code user_data}. */
    static final String MARK_KEY = "dungeontrain_carriage";

    enum Verdict { KEEP, REAP_MARKED, REAP_OLD_SAVE }

    private DriverlessCarriageRule() {}

    /** A copy of {@code userData} carrying the carriage marker; the original is left as it was. */
    static CompoundTag withMark(@Nullable CompoundTag userData) {
        CompoundTag marked = userData == null ? new CompoundTag() : userData.copy();
        marked.putBoolean(MARK_KEY, true);
        return marked;
    }

    static boolean isMarked(@Nullable CompoundTag userData) {
        return userData != null && userData.getBoolean(MARK_KEY);
    }

    /**
     * @param sizeX,sizeY,sizeZ the sub-level's plot size in blocks
     * @param trainWorld whether this world runs a train at all
     * @param halfPadLen the flatbed half each end of a group may carry
     */
    static Verdict judge(boolean hasDriver, boolean marked, boolean trainWorld,
                         int sizeX, int sizeY, int sizeZ,
                         int carriageLength, int carriageWidth, int carriageHeight,
                         int groupSize, int halfPadLen) {
        if (hasDriver) return Verdict.KEEP;
        if (marked) return Verdict.REAP_MARKED;
        if (!trainWorld || sizeY != carriageHeight || sizeZ != carriageWidth) return Verdict.KEEP;
        int bare = groupSize * carriageLength;
        return sizeX >= bare && sizeX <= bare + 2 * halfPadLen ? Verdict.REAP_OLD_SAVE : Verdict.KEEP;
    }
}
