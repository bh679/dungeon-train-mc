package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonObject;
import games.brennan.pigmanvillagers.api.PigmanVillagersApi;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a shot had in it, carried from the moment it is taken to the moment its print uploads, for the
 * relay's photo leaderboards (Most Passenger Portraits, Bravest Photographer, Wildlife Photographer) —
 * and which Enchiridion advancements the shot earned its photographer, for the website's Tributes
 * page (recorded by {@link AdvancementPhotoCapture} as they are granted).
 *
 * <p>Only Exposure's {@code FrameAddedEvent} knows the entities in frame, and it fires at the shot; the
 * disposable camera prints — and {@link SharedPhotos#queueUpload} runs — some ticks later. So the shot's
 * {@link Subjects} wait here, keyed by the frame's exposure id, and the upload takes them. In memory
 * only, bounded and short-lived: a restart between shot and print just loses one photo's subjects.</p>
 */
public final class PhotoSubjectTally {

    /** How long a shot's subjects wait for their print. A disposable camera prints in seconds. */
    static final long TTL_MS = 10 * 60 * 1000L;
    /** The most shots held at once; the oldest goes first. */
    static final int MAX_ENTRIES = 256;
    /** The most advancements one shot carries to the relay (it keeps at most this many too). */
    static final int MAX_ADVANCEMENTS = 20;

    /**
     * Whether the frame held a passenger (another player, a PlayerMob — echoes included — or a
     * villager, pigmen too), a hostile mob, or a peaceful creature. A photo counts once per board.
     */
    public record Subjects(boolean passengers, boolean hostile, boolean animals) {
        public static final Subjects NONE = new Subjects(false, false, false);

        public boolean any() { return passengers || hostile || animals; }

        Subjects or(Subjects o) {
            return new Subjects(passengers || o.passengers, hostile || o.hostile, animals || o.animals);
        }

        /** {@code {"passengers":true, …}} with only the true flags — the relay's {@code meta.subjects}. */
        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            if (passengers) out.addProperty("passengers", true);
            if (hostile) out.addProperty("hostile", true);
            if (animals) out.addProperty("animals", true);
            return out;
        }
    }

    /** Everything held for one shot: its subjects and the advancements it earned, in the order earned. */
    public record Shot(Subjects subjects, List<String> advancements) {
        public static final Shot NONE = new Shot(Subjects.NONE, List.of());
    }

    private record Entry(Subjects subjects, List<String> advancements, long ts) {}

    private static final Map<String, Entry> PENDING = new LinkedHashMap<>();

    private PhotoSubjectTally() {}

    /** One entity's kind, from plain facts — kept apart from the entity so it can be tested. */
    static Subjects classify(boolean player, boolean playerMob, boolean villager, boolean enemy, boolean creature) {
        return new Subjects(player || playerMob || villager, enemy, creature && !enemy);
    }

    /** The subjects of a shot: everything in frame except the photographer. */
    public static Subjects of(LivingEntity photographer, List<LivingEntity> inFrame) {
        Subjects out = Subjects.NONE;
        for (LivingEntity e : inFrame) {
            if (e == photographer) continue;
            out = out.or(classify(
                    e instanceof Player,
                    e instanceof PlayerMobEntity,
                    e instanceof AbstractVillager || PigmanVillagersApi.isPigman(e),
                    e instanceof Enemy,
                    e instanceof Animal || e instanceof WaterAnimal || e instanceof AmbientCreature));
        }
        return out;
    }

    /** Hold a shot's subjects for its print. A shot with none is not held (unless it earned something). */
    public static void record(String exposureId, Subjects subjects) {
        record(exposureId, subjects, System.currentTimeMillis());
    }

    static synchronized void record(String exposureId, Subjects subjects, long now) {
        if (exposureId == null || exposureId.isBlank() || subjects == null || !subjects.any()) return;
        prune(now);
        Entry held = PENDING.remove(exposureId);
        put(exposureId, new Entry(subjects, held == null ? List.of() : held.advancements(), now));
    }

    /** Note an advancement the shot just earned its photographer, for its print's upload. */
    public static void recordAdvancement(String exposureId, String advancement) {
        recordAdvancement(exposureId, advancement, System.currentTimeMillis());
    }

    static synchronized void recordAdvancement(String exposureId, String advancement, long now) {
        if (exposureId == null || exposureId.isBlank() || advancement == null || advancement.isBlank()) return;
        prune(now);
        Entry held = PENDING.remove(exposureId);
        List<String> earned = held == null ? List.of() : held.advancements();
        if (!earned.contains(advancement) && earned.size() < MAX_ADVANCEMENTS) {
            List<String> next = new ArrayList<>(earned);
            next.add(advancement);
            earned = List.copyOf(next);
        }
        put(exposureId, new Entry(held == null ? Subjects.NONE : held.subjects(), earned, now));
    }

    private static void put(String exposureId, Entry entry) {
        PENDING.put(exposureId, entry);
        while (PENDING.size() > MAX_ENTRIES) {
            Iterator<String> it = PENDING.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    /** The subjects held for this exposure, removed; {@link Subjects#NONE} when there are none. */
    public static Subjects take(String exposureId) {
        return take(exposureId, System.currentTimeMillis());
    }

    static synchronized Subjects take(String exposureId, long now) {
        return takeShot(exposureId, now).subjects();
    }

    /** Everything held for this exposure — subjects and advancements — removed; {@link Shot#NONE} if nothing. */
    public static Shot takeShot(String exposureId) {
        return takeShot(exposureId, System.currentTimeMillis());
    }

    static synchronized Shot takeShot(String exposureId, long now) {
        prune(now);
        Entry e = exposureId == null ? null : PENDING.remove(exposureId);
        return e == null ? Shot.NONE : new Shot(e.subjects(), e.advancements());
    }

    private static void prune(long now) {
        PENDING.values().removeIf(e -> now - e.ts() > TTL_MS);
    }

    /** Tests only. */
    static synchronized void clear() { PENDING.clear(); }

    static synchronized int size() { return PENDING.size(); }
}
