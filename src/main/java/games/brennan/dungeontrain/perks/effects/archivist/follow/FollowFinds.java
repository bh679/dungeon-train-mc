package games.brennan.dungeontrain.perks.effects.archivist.follow;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What this life found from other players: how many community books and photos per author token
 * (the Followed Authors menu's "K found this life"), and the names of the players behind them (the
 * Follow box's autocomplete). Per life: not copied on death. Immutable.
 */
public record FollowFinds(Map<String, Integer> byToken, List<String> names) {

    /** Most names kept for autocomplete; the oldest drop first. */
    static final int MAX_NAMES = 64;

    public static final Codec<FollowFinds> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("by_token", Map.of()).forGetter(FollowFinds::byToken),
        Codec.STRING.listOf().optionalFieldOf("names", List.of()).forGetter(FollowFinds::names)
    ).apply(i, FollowFinds::new));

    public FollowFinds {
        byToken = Map.copyOf(byToken);
        names = List.copyOf(names);
    }

    public static FollowFinds empty() {
        return new FollowFinds(Map.of(), List.of());
    }

    public boolean isEmpty() {
        return byToken.isEmpty() && names.isEmpty();
    }

    public int count(String token) {
        return token == null ? 0 : byToken.getOrDefault(token, 0);
    }

    /** One more find by {@code token} (blank: no count) from the player named {@code name} (blank: no name). */
    public FollowFinds found(String token, String name) {
        Map<String, Integer> counts = new HashMap<>(byToken);
        if (token != null && !token.isBlank()) counts.merge(token, 1, Integer::sum);
        List<String> list = new ArrayList<>(names);
        if (name != null && !name.isBlank()) {
            list.removeIf(n -> n.equalsIgnoreCase(name));
            list.add(name);
            while (list.size() > MAX_NAMES) list.remove(0);
        }
        return new FollowFinds(counts, list);
    }

    /** Names starting with {@code prefix} (case-insensitive), most recent first. */
    public List<String> suggestions(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (int i = names.size() - 1; i >= 0; i--) {
            if (names.get(i).toLowerCase(Locale.ROOT).startsWith(p)) out.add(names.get(i));
        }
        return out;
    }
}
