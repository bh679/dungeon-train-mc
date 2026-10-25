package games.brennan.dungeontrain.perks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A player's perk state: which perks are <b>unlocked</b> (bought with unlock points; survives
 * death) and how many <b>active points</b> (the rank) each holds this life (reset to nothing on
 * death). Immutable: every mutator returns a new state.
 *
 * <p>Persisted on the player as {@code ModDataAttachments.PERK_STATE} ({@code copyOnDeath}, the
 * respawn hook then clears the active map) and mirrored to the owning client by
 * {@code PerkStatePacket}. Both codec fields are optional so a save written before a field
 * existed still decodes.</p>
 */
public final class PerkState {

    public static final PerkState EMPTY = new PerkState(Set.of(), Map.of());

    public static final Codec<PerkState> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.listOf().optionalFieldOf("unlocked", List.of())
            .forGetter(s -> List.copyOf(s.unlocked)),
        Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("active", Map.of())
            .forGetter(s -> s.active)
    ).apply(i, (unlocked, active) -> new PerkState(new LinkedHashSet<>(unlocked), active)));

    public static final StreamCodec<ByteBuf, PerkState> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), s -> List.copyOf(s.unlocked),
        ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT), s -> s.active,
        (unlocked, active) -> new PerkState(new LinkedHashSet<>(unlocked), active));

    private final Set<String> unlocked;
    private final Map<String, Integer> active;

    private PerkState(Set<String> unlocked, Map<String, Integer> active) {
        this.unlocked = Collections.unmodifiableSet(new LinkedHashSet<>(unlocked));
        Map<String, Integer> positive = new LinkedHashMap<>();
        active.forEach((id, pts) -> { if (pts != null && pts > 0) positive.put(id, pts); });
        this.active = Collections.unmodifiableMap(positive);
    }

    public static PerkState empty() {
        return EMPTY;
    }

    // ---- reads ----

    public boolean isUnlocked(String perkId) {
        return unlocked.contains(perkId);
    }

    /** Active points held, whether or not the perk is unlocked. */
    public int activePoints(String perkId) {
        return active.getOrDefault(perkId, 0);
    }

    /** The rank effects scale with: active points, but only while the perk is unlocked. */
    public int rank(String perkId) {
        return unlocked.contains(perkId) ? activePoints(perkId) : 0;
    }

    public Set<String> unlocked() {
        return unlocked;
    }

    public Map<String, Integer> active() {
        return active;
    }

    public boolean isEmpty() {
        return unlocked.isEmpty() && active.isEmpty();
    }

    // ---- writes (return a new state) ----

    public PerkState withUnlocked(String perkId) {
        if (unlocked.contains(perkId)) return this;
        Set<String> next = new LinkedHashSet<>(unlocked);
        next.add(perkId);
        return new PerkState(next, active);
    }

    public PerkState withoutUnlocked(String perkId) {
        if (!unlocked.contains(perkId)) return this;
        Set<String> next = new LinkedHashSet<>(unlocked);
        next.remove(perkId);
        Map<String, Integer> nextActive = new LinkedHashMap<>(active);
        nextActive.remove(perkId);
        return new PerkState(next, nextActive);
    }

    /** Set active points; zero or less removes the entry. */
    public PerkState withActive(String perkId, int points) {
        if (activePoints(perkId) == Math.max(0, points)) return this;
        Map<String, Integer> next = new LinkedHashMap<>(active);
        if (points > 0) next.put(perkId, points); else next.remove(perkId);
        return new PerkState(unlocked, next);
    }

    /** New life: every active point is gone, the unlocked set stays. */
    public PerkState clearedActive() {
        return active.isEmpty() ? this : new PerkState(unlocked, Map.of());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PerkState s && unlocked.equals(s.unlocked) && active.equals(s.active);
    }

    @Override
    public int hashCode() {
        return 31 * unlocked.hashCode() + active.hashCode();
    }

    @Override
    public String toString() {
        return "PerkState{unlocked=" + unlocked + ", active=" + active + "}";
    }
}
