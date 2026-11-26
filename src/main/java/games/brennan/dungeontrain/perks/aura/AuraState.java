package games.brennan.dungeontrain.perks.aura;

import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A player's auras, per aura perk id: this life's draws, and which effects they switched off. Two
 * attachments, because they live differently: draws are per life (not copied on death), toggles
 * survive death. Immutable values; every change is a copy.
 */
public final class AuraState {

    private AuraState() {}

    /** Per-life draws by aura id. */
    public record Draws(Map<String, AuraDraws> byAura) {
        public static final Codec<Draws> CODEC = Codec.unboundedMap(Codec.STRING, AuraDraws.CODEC)
            .xmap(Draws::new, Draws::byAura);

        public Draws {
            byAura = Map.copyOf(byAura);
        }

        public static Draws empty() {
            return new Draws(Map.of());
        }

        public AuraDraws of(String auraId) {
            return byAura.getOrDefault(auraId, AuraDraws.EMPTY);
        }

        public Draws with(String auraId, AuraDraws draws) {
            Map<String, AuraDraws> next = new HashMap<>(byAura);
            next.put(auraId, draws);
            return new Draws(next);
        }

        public boolean isEmpty() {
            return byAura.isEmpty();
        }
    }

    /** Switched-off effects by aura id; kept across deaths. */
    public record Toggles(Map<String, Set<ResourceLocation>> offByAura) {
        public static final Codec<Toggles> CODEC = Codec.unboundedMap(Codec.STRING,
                ResourceLocation.CODEC.listOf().xmap(Set::copyOf, java.util.List::copyOf))
            .xmap(Toggles::new, Toggles::offByAura);

        public Toggles {
            Map<String, Set<ResourceLocation>> copy = new HashMap<>();
            offByAura.forEach((k, v) -> { if (!v.isEmpty()) copy.put(k, Set.copyOf(v)); });
            offByAura = Map.copyOf(copy);
        }

        public static Toggles empty() {
            return new Toggles(Map.of());
        }

        public Set<ResourceLocation> off(String auraId) {
            return offByAura.getOrDefault(auraId, Set.of());
        }

        public boolean isOff(String auraId, ResourceLocation effect) {
            return off(auraId).contains(effect);
        }

        public Toggles flipped(String auraId, ResourceLocation effect) {
            Set<ResourceLocation> set = new HashSet<>(off(auraId));
            if (!set.remove(effect)) set.add(effect);
            Map<String, Set<ResourceLocation>> next = new HashMap<>(offByAura);
            next.put(auraId, set);
            return new Toggles(next);
        }

        public boolean isEmpty() {
            return offByAura.isEmpty();
        }
    }
}
