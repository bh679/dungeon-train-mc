package games.brennan.dungeontrain.perks;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * The perks each past life activated, oldest life first, one list of perk ids per life (the order
 * they were activated, where known). Drawn as that life's path on the nap sky
 * ({@code client/nap/NapSkyPainter}). A life ended before perks has no entry; a life with perks that
 * activated none has an empty one. Keeps the newest {@link #MAX_LIVES}. Immutable.
 *
 * <p>Saved in the player's perk file ({@link PerkProfileStore}), synced by {@code PerkLivesPacket}.</p>
 */
public final class PerkLifeHistory {

    /** Lives kept: the nap sky draws no more than this many. */
    public static final int MAX_LIVES = 24;
    /** Perk ids kept per life, a bound on a hand-edited or hostile file. */
    public static final int MAX_PERKS_PER_LIFE = 256;

    public static final PerkLifeHistory EMPTY = new PerkLifeHistory(List.of());

    private final List<List<String>> lives;

    private PerkLifeHistory(List<List<String>> lives) {
        List<List<String>> out = new ArrayList<>();
        int from = Math.max(0, lives.size() - MAX_LIVES);
        for (int i = from; i < lives.size(); i++) out.add(clean(lives.get(i)));
        this.lives = List.copyOf(out);
    }

    public static PerkLifeHistory of(List<List<String>> lives) {
        return lives == null || lives.isEmpty() ? EMPTY : new PerkLifeHistory(lives);
    }

    private static List<String> clean(List<String> ids) {
        if (ids == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (id != null && !id.isEmpty() && !out.contains(id) && out.size() < MAX_PERKS_PER_LIFE) out.add(id);
        }
        return List.copyOf(out);
    }

    /** Past lives, oldest first. */
    public List<List<String>> lives() {
        return lives;
    }

    public int size() {
        return lives.size();
    }

    /** One more life ended, having activated {@code perks}; the oldest drops past {@link #MAX_LIVES}. */
    public PerkLifeHistory withLife(List<String> perks) {
        List<List<String>> next = new ArrayList<>(lives);
        next.add(perks == null ? List.of() : perks);
        return new PerkLifeHistory(next);
    }

    // ---- NBT: a list of lists of strings ----

    public ListTag toTag() {
        ListTag out = new ListTag();
        for (List<String> life : lives) {
            ListTag ids = new ListTag();
            life.forEach(id -> ids.add(StringTag.valueOf(id)));
            out.add(ids);
        }
        return out;
    }

    /** Read {@code key} from {@code parent}; missing or malformed reads as {@link #EMPTY}. */
    public static PerkLifeHistory fromTag(CompoundTag parent, String key) {
        if (!parent.contains(key, Tag.TAG_LIST)) return EMPTY;
        ListTag list = parent.getList(key, Tag.TAG_LIST);
        List<List<String>> lives = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ListTag ids = list.getList(i);
            List<String> life = new ArrayList<>();
            for (int j = 0; j < ids.size(); j++) life.add(ids.getString(j));
            lives.add(life);
        }
        return of(lives);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PerkLifeHistory h && lives.equals(h.lives);
    }

    @Override
    public int hashCode() {
        return lives.hashCode();
    }

    @Override
    public String toString() {
        return "PerkLifeHistory" + lives;
    }
}
