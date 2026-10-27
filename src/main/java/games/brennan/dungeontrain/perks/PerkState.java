package games.brennan.dungeontrain.perks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A player's perk state: how many times each perk is <b>unlocked</b> (its unlock level, bought with
 * Advancement Points; survives death) and how many <b>active points</b> (the rank) each holds this
 * life (reset to nothing on death). A perk's unlock level is the most points it can hold
 * ({@link PerkUnlocks}). Immutable: every mutator returns a new state.
 *
 * <p>Persisted on the player as {@code ModDataAttachments.PERK_STATE} ({@code copyOnDeath}, the
 * respawn hook then clears the active map) and mirrored to the owning client by
 * {@code PerkStatePacket}. Every codec field is optional so an older save still decodes; a save from
 * before repeat unlocks carries an {@code unlocked} list, read as level 1 each.</p>
 */
public final class PerkState {

    public static final PerkState EMPTY = new PerkState(Map.of(), Map.of());

    public static final Codec<PerkState> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.listOf().optionalFieldOf("unlocked", List.of()).forGetter(s -> List.of()),
        Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("unlocks", Map.of())
            .forGetter(s -> s.unlocks),
        Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("active", Map.of())
            .forGetter(s -> s.active)
    ).apply(i, (legacy, unlocks, active) -> new PerkState(merge(legacy, unlocks), active)));

    public static final StreamCodec<ByteBuf, PerkState> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT), s -> s.unlocks,
        ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT), s -> s.active,
        PerkState::new);

    private final Map<String, Integer> unlocks;
    private final Map<String, Integer> active;

    private PerkState(Map<String, Integer> unlocks, Map<String, Integer> active) {
        this.unlocks = Collections.unmodifiableMap(positive(unlocks));
        this.active = Collections.unmodifiableMap(positive(active));
    }

    private static Map<String, Integer> positive(Map<String, Integer> in) {
        Map<String, Integer> out = new LinkedHashMap<>();
        in.forEach((id, n) -> { if (n != null && n > 0) out.put(id, n); });
        return out;
    }

    /** A pre-repeat-unlock save's list counts as level 1 each; the new map wins where both name a perk. */
    private static Map<String, Integer> merge(List<String> legacy, Map<String, Integer> unlocks) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String id : legacy) out.put(id, 1);
        out.putAll(unlocks);
        return out;
    }

    public static PerkState empty() {
        return EMPTY;
    }

    // ---- reads ----

    public boolean isUnlocked(String perkId) {
        return unlockLevel(perkId) > 0;
    }

    /** Times this perk is unlocked: the most active points it can hold. 0 when locked. */
    public int unlockLevel(String perkId) {
        return unlocks.getOrDefault(perkId, 0);
    }

    /** Active points held, whether or not the perk is unlocked. */
    public int activePoints(String perkId) {
        return active.getOrDefault(perkId, 0);
    }

    /**
     * The rank effects scale with: active points, but only while the perk is unlocked. Writes through
     * {@code Perks} keep points at or under the unlock level.
     */
    public int rank(String perkId) {
        return isUnlocked(perkId) ? activePoints(perkId) : 0;
    }

    public Set<String> unlocked() {
        return unlocks.keySet();
    }

    public Map<String, Integer> unlocks() {
        return unlocks;
    }

    public Map<String, Integer> active() {
        return active;
    }

    public boolean isEmpty() {
        return unlocks.isEmpty() && active.isEmpty();
    }

    // ---- writes (return a new state) ----

    /** Unlock once more: the unlock level goes up by one. */
    public PerkState withUnlockedOnce(String perkId) {
        return withUnlockLevel(perkId, unlockLevel(perkId) + 1);
    }

    /** First unlock only; a perk already unlocked keeps its level. */
    public PerkState withUnlocked(String perkId) {
        return isUnlocked(perkId) ? this : withUnlockLevel(perkId, 1);
    }

    /** Set the unlock level; zero or less locks the perk and drops its points. */
    public PerkState withUnlockLevel(String perkId, int level) {
        if (unlockLevel(perkId) == Math.max(0, level)) return this;
        Map<String, Integer> next = new LinkedHashMap<>(unlocks);
        Map<String, Integer> nextActive = new LinkedHashMap<>(active);
        if (level > 0) {
            next.put(perkId, level);
        } else {
            next.remove(perkId);
            nextActive.remove(perkId);
        }
        return new PerkState(next, nextActive);
    }

    public PerkState withoutUnlocked(String perkId) {
        return withUnlockLevel(perkId, 0);
    }

    /** Set active points; zero or less removes the entry. */
    public PerkState withActive(String perkId, int points) {
        if (activePoints(perkId) == Math.max(0, points)) return this;
        Map<String, Integer> next = new LinkedHashMap<>(active);
        if (points > 0) next.put(perkId, points); else next.remove(perkId);
        return new PerkState(unlocks, next);
    }

    /** New life: every active point is gone, the unlock levels stay. */
    public PerkState clearedActive() {
        return active.isEmpty() ? this : new PerkState(unlocks, Map.of());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PerkState s && unlocks.equals(s.unlocks) && active.equals(s.active);
    }

    @Override
    public int hashCode() {
        return 31 * unlocks.hashCode() + active.hashCode();
    }

    @Override
    public String toString() {
        return "PerkState{unlocks=" + unlocks + ", active=" + active + "}";
    }
}
