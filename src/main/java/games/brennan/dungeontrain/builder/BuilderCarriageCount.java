package games.brennan.dungeontrain.builder;

import javax.annotation.Nullable;

/**
 * How many carriages a builder world actually parks — mode <em>and</em> what is being built.
 *
 * <p>{@link BuilderMode#carriageCount()} answers this on its own for a carriage: Train Outside
 * parks a run of three wrapped in flatbed pads, because judging a shell's silhouette means seeing
 * it repeat. A <b>carriage room</b> is not that kind of thing. It is the interior of one carriage —
 * {@code BuilderSave.save} picks a single volume and writes only that one — so a run of three
 * showed the same room three times, saved one of them, and made the other two look like part of
 * the build.</p>
 *
 * <p>Hence: the count is a function of the pair. Everything that needs to know where the build is —
 * the stamp, the bounds packet, the dirty check, the save target, the cinematic framing — derives
 * it here from {@code builderMode} and {@code builderSubType}, both of which are already persisted
 * in {@code DungeonTrainWorldData}. One rule, one place; a consumer that disagreed with the stamp
 * would point the out-of-bounds wash or the save at a volume with nothing in it.</p>
 *
 * <p>Pure — no level access, no client imports — so the rule is unit-testable.</p>
 */
public final class BuilderCarriageCount {

    private BuilderCarriageCount() {}

    /**
     * How many carriages this build parks.
     *
     * <p>A carriage room clamps the mode's count to one rather than hard-coding it: whether there
     * is a carriage at all stays {@link BuilderMode}'s call, so the track and portal modes still
     * answer zero and {@code BuilderWorldSetup.applyOpen}'s "no carriage to open into" refusal is
     * unaffected. This only narrows how many.</p>
     *
     * @param mode      the world's builder mode, or null outside a builder world
     * @param subTypeId {@code DungeonTrainWorldData#builderSubType()} — null on a world that has
     *                  not recorded one yet, which reads as "not a room" and keeps the mode's count
     */
    public static int of(@Nullable BuilderMode mode, @Nullable String subTypeId) {
        if (mode == null) {
            return 0;
        }
        int parked = mode.carriageCount();
        return isCarriageRoom(subTypeId) ? Math.min(parked, 1) : parked;
    }

    /** Convenience for the callers that hold a mode id off the wire or out of world data. */
    public static int of(@Nullable String modeId, @Nullable String subTypeId) {
        return BuilderMode.fromId(modeId).map(mode -> of(mode, subTypeId)).orElse(0);
    }

    private static boolean isCarriageRoom(@Nullable String subTypeId) {
        return BuilderNewOptions.SubType.CARRIAGE_ROOM.id().equals(subTypeId);
    }
}
