package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.EnumSet;
import java.util.Set;

/**
 * One creaking heart's DT settings, edited from the Creative right-click menu
 * ({@code client/menu/creakingheart/CreakingHeartPanel}) and saved on the heart's block entity under
 * {@link #TAG}, so they travel inside a contents template's {@code .nbt} and onto every train it
 * is stamped onto.
 *
 * <ul>
 *   <li>{@code day} / {@code night}: whether the heart is awake by day and by night.</li>
 *   <li>{@code count}: how many Creaking it keeps (1 is VanillaBackport's own one).</li>
 *   <li>{@code gate} + {@code stageId}: the Diff-Level range and bands it may wake in, or a linked
 *       Stage (blank = Custom), resolved like a template's gate. Decided once, when the heart is
 *       stamped onto a carriage; {@code gatedOff} records a heart that failed it.</li>
 * </ul>
 *
 * <p>{@link #DEFAULT} is all-on, one Creaking, every level and band, which is how a heart behaved
 * before these settings existed, so a heart without the tag is unchanged.</p>
 */
public record CreakingHeartSettings(boolean day, boolean night, int count, TemplateGate gate, String stageId,
                                    boolean gatedOff) {

    /** The block-entity compound key. */
    public static final String TAG = "dungeontrain:heart";
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 8;

    public static final CreakingHeartSettings DEFAULT =
        new CreakingHeartSettings(true, true, 1, TemplateGate.DEFAULT, "", false);

    public CreakingHeartSettings {
        count = clampCount(count);
        gate = gate == null ? TemplateGate.DEFAULT : gate;
        stageId = stageId == null ? "" : stageId;
    }

    public static int clampCount(int count) {
        return Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));
    }

    /** Whether the heart is awake now, given whether the moon is up. */
    public boolean awake(boolean moonVisible) {
        if (gatedOff) return false;
        return moonVisible ? night : day;
    }

    public CreakingHeartSettings withDay(boolean v) { return new CreakingHeartSettings(v, night, count, gate, stageId, gatedOff); }
    public CreakingHeartSettings withNight(boolean v) { return new CreakingHeartSettings(day, v, count, gate, stageId, gatedOff); }
    public CreakingHeartSettings withCount(int v) { return new CreakingHeartSettings(day, night, v, gate, stageId, gatedOff); }
    public CreakingHeartSettings withGate(TemplateGate v) { return new CreakingHeartSettings(day, night, count, v, stageId, gatedOff); }
    public CreakingHeartSettings withStage(String v) { return new CreakingHeartSettings(day, night, count, gate, v, gatedOff); }
    public CreakingHeartSettings withGatedOff(boolean v) { return new CreakingHeartSettings(day, night, count, gate, stageId, v); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("day", day);
        tag.putBoolean("night", night);
        tag.putInt("count", count);
        tag.putInt("minLevel", gate.minLevel());
        tag.putInt("maxLevel", gate.maxLevel());
        if (!gate.phases().equals(TemplateGate.ALL_PHASES)) {
            ListTag phases = new ListTag();
            for (TrainPhase p : gate.phases()) phases.add(StringTag.valueOf(p.token()));
            tag.put("phases", phases);
        }
        if (!stageId.isEmpty()) tag.putString("stage", stageId);
        if (gatedOff) tag.putBoolean("gatedOff", true);
        return tag;
    }

    /** Reads {@link #save}'s compound; anything missing falls back to {@link #DEFAULT}'s value. */
    public static CreakingHeartSettings load(CompoundTag tag) {
        boolean day = !tag.contains("day") || tag.getBoolean("day");
        boolean night = !tag.contains("night") || tag.getBoolean("night");
        int count = tag.contains("count") ? tag.getInt("count") : 1;
        int min = tag.contains("minLevel") ? tag.getInt("minLevel") : 0;
        int max = tag.contains("maxLevel") ? tag.getInt("maxLevel") : TemplateGate.ALL;
        Set<TrainPhase> phases = EnumSet.noneOf(TrainPhase.class);
        for (Tag t : tag.getList("phases", Tag.TAG_STRING)) {
            TrainPhase p = TrainPhase.byToken(t.getAsString());
            if (p != null) phases.add(p);
        }
        return new CreakingHeartSettings(day, night, count, new TemplateGate(min, max, phases),
            tag.getString("stage"), tag.getBoolean("gatedOff"));
    }
}
